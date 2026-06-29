package `in`.jphe.storyvox.playback.auto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FollowsRepository
import `in`.jphe.storyvox.data.repository.LibraryRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.AutoBrowserConfig
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.playback.MediaSessionLocator
import `in`.jphe.storyvox.playback.StoryvoxPlaybackService
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Issue #598 / #1232 — Android Auto browse tree:
 *
 * ```
 *   /                         (root)
 *   ├── /library              (browsable tab → books → chapters)
 *   ├── /follows              (browsable tab → books → chapters)
 *   ├── /recent               (browsable tab → playable chapters)
 *   └── /new                  (browsable tab → playable chapters)
 * ```
 *
 * Four tabs because Android Auto caps the root menu at 4. "Continue where I
 * left off" is served by the session callback's `onPlaybackResumption`
 * (Auto's native resume affordance) rather than a browse node — see
 * [`in`.jphe.storyvox.playback.StoryvoxSessionCallback].
 *
 * #1232 wires up the two pieces the prior browse-only scaffold left out:
 * 1. [setSessionToken] — bridged from the Media3 session's platform token via
 *    [MediaSessionLocator.compatToken]; without it Auto can browse but can't
 *    control playback.
 * 2. The book→chapter level (`/library/<f>` → `/library/<f>/<c>`), so tapping a
 *    library/follows book opens its (playable) chapters instead of dead-ending.
 *
 * Media-id strings are built + parsed through [AutoMediaId] so the browse side
 * and the play side can't drift.
 */
@AndroidEntryPoint
class StoryvoxAutoBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var libraryRepo: LibraryRepository
    @Inject lateinit var followsRepo: FollowsRepository
    @Inject lateinit var positionRepo: PlaybackPositionRepository
    @Inject lateinit var chapterRepo: ChapterRepository
    @Inject lateinit var sessionLocator: MediaSessionLocator
    /** Issue #598 — user-tunable bucket size. Read at every [onLoadChildren]
     *  call so a Settings flip takes effect the next time Auto refreshes the
     *  tree. */
    @Inject lateinit var autoConfig: AutoBrowserConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // #1232 — nudge the playback service to build its MediaSession (and
        // publish the legacy token) so transport controls can bind even when
        // the user opened the app via Auto's browse UI. Guarded: background
        // start can be refused on some OS versions; the token collector below
        // still attaches the token whenever the session does come up.
        runCatching {
            startService(Intent(this, StoryvoxPlaybackService::class.java))
        }
        // Attach the session token as soon as the playback service publishes
        // it. setSessionToken must run on the main thread.
        scope.launch {
            val token = sessionLocator.compatToken.filterNotNull().first()
            withContext(Dispatchers.Main) { setSessionToken(token) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // list
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)  // grid
            // #1232 — advertise that the session handles voice search so Auto
            // routes "play <title>" through playFromSearch.
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
        }
        return BrowserRoot(AutoMediaId.ROOT, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        scope.launch {
            val bucket = autoConfig.currentItemsPerCategory()
            val items = when (val node = AutoMediaId.parse(parentId)) {
                is AutoMediaId.Node.Root -> rootCategories()
                is AutoMediaId.Node.Category -> categoryChildren(node.category, bucket)
                is AutoMediaId.Node.Book -> bookChapters(node.category, node.fictionId)
                // Chapter / Resume / unparseable have no children.
                else -> emptyList()
            }
            result.sendResult(items.toMutableList())
        }
    }

    /** The four browsable root tabs (Auto caps the root at 4). */
    private fun rootCategories(): List<MediaBrowserCompat.MediaItem> = listOf(
        browsable(AutoMediaId.LIBRARY, "Library"),
        browsable(AutoMediaId.FOLLOWS, "Follows"),
        browsable(AutoMediaId.RECENT, "Recent"),
        browsable(AutoMediaId.NEW, "New chapters"),
    )

    private suspend fun categoryChildren(
        category: String,
        bucket: Int,
    ): List<MediaBrowserCompat.MediaItem> = when (category) {
        AutoMediaId.LIBRARY -> libraryRepo.snapshot().take(bucket)
            .map { browsableBook(category, it.id, it.title, it.author, it.coverUrl) }
        AutoMediaId.FOLLOWS -> followsRepo.snapshot().take(bucket)
            .map { browsableBook(category, it.id, it.title, it.author, it.coverUrl) }
        AutoMediaId.RECENT -> positionRepo.recent(bucket)
            .map { playableChapter(AutoMediaId.RECENT, it.fictionId, it.chapterId, it.chapterTitle, it.bookTitle, it.coverUrl) }
        AutoMediaId.NEW -> followsRepo.unreadChapters(bucket)
            .map { playableChapter(AutoMediaId.NEW, it.fictionId, it.chapterId, it.chapterTitle, it.bookTitle, it.coverUrl) }
        else -> emptyList()
    }

    /** A book's chapters, as playable leaves. Capped so a 2000-chapter web
     *  serial doesn't build thousands of MediaItems (Auto truncates anyway). */
    private suspend fun bookChapters(
        category: String,
        fictionId: String,
    ): List<MediaBrowserCompat.MediaItem> {
        val chapters: List<ChapterInfo> = runCatching {
            chapterRepo.observeChapters(fictionId).first()
        }.getOrDefault(emptyList())
        return chapters.sortedBy { it.index }.take(CHAPTER_LIMIT).map { ch ->
            playableChapter(category, fictionId, ch.id, ch.title, subtitle = null, coverUrl = null)
        }
    }

    private fun browsable(id: String, title: String): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    /** A book node — browsable, drills into [bookChapters]. */
    private fun browsableBook(
        category: String,
        fictionId: String,
        title: String,
        author: String?,
        coverUrl: String?,
    ): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(AutoMediaId.book(category, fictionId))
            .setTitle(title)
            .setSubtitle(author)
            .setIconUri(coverUrl?.let { Uri.parse(it) })
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    /** A chapter node — playable; tapping routes to playFromMediaId. */
    private fun playableChapter(
        category: String,
        fictionId: String,
        chapterId: String,
        title: String,
        subtitle: String?,
        coverUrl: String?,
    ): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(AutoMediaId.chapter(category, fictionId, chapterId))
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(coverUrl?.let { Uri.parse(it) })
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    companion object {
        /** HMI guideline default bucket size; the live value comes from
         *  [AutoBrowserConfig.currentItemsPerCategory]. Referenced by the
         *  Settings copy in AdvancedSettingsScreen. */
        const val MAX_PER_CATEGORY = 6

        /** Max chapters listed under one book before we stop building items. */
        const val CHAPTER_LIMIT = 200
    }
}
