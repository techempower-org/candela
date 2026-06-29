package `in`.jphe.storyvox.playback

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import `in`.jphe.storyvox.playback.auto.AutoMediaId
import `in`.jphe.storyvox.playback.auto.AutoPlaybackResolver
import `in`.jphe.storyvox.playback.auto.PlayTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Issue #216 / #1232 — the Media3 session callback.
 *
 * `onMediaButtonEvent` handles BT/lock-screen keys (pre-existing). Issue #1232
 * adds the **play-from-Android-Auto** path: when a legacy controller (Android
 * Auto, Google Assistant) issues `playFromMediaId` / `playFromSearch`, Media3
 * routes it into [onSetMediaItems] / [onAddMediaItems] with the browse media id
 * in [MediaItem.mediaId] and any voice query in
 * `MediaItem.requestMetadata.searchQuery`.
 *
 * Because [`in`.jphe.storyvox.playback.tts.EnginePlayer] is a `SimpleBasePlayer`
 * that builds its **own** playlist (it has no externally-set media items — it
 * synthesises TTS from chapter text), we don't hand Media3 a URI to play.
 * Instead we resolve the request to a (fiction, chapter, offset) and drive
 * playback through [DefaultPlaybackController.play] as a side effect; the player
 * then reports its own timeline/metadata, which the session broadcasts back to
 * Auto. The items we return are protocol echoes carrying the canonical media id.
 *
 * NOTE (on-device verify via DHU): the resolve→`controller.play` side effect is
 * the source of truth for playback; Media3's attempt to apply the returned
 * items to the player is expected to be a no-op since the player doesn't expose
 * `COMMAND_SET_MEDIA_ITEM`. Validate the end-to-end "tap a book / say 'play X' /
 * resume" flow on a real head unit (DHU) — see issue #1232 testing notes.
 */
@UnstableApi
class StoryvoxSessionCallback(
    private val controller: DefaultPlaybackController,
    private val scope: CoroutineScope,
    private val autoResolver: AutoPlaybackResolver,
) : MediaSession.Callback {

    private val mediaButtonHandler = MediaButtonHandler(controller, scope)

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            ?: return false
        return mediaButtonHandler.handle(event)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val future = SettableFuture.create<MutableList<MediaItem>>()
        scope.launch {
            resolveAndPlay(mediaItems.firstOrNull())
            // Echo the request back; EnginePlayer drives its own timeline.
            future.set(mediaItems)
        }
        return future
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            // Auto may send C.INDEX_UNSET (-1); coerce to a valid index so
            // MediaItemsWithStartPosition doesn't reject it.
            val safeIndex = if (startIndex in mediaItems.indices) startIndex else 0
            resolveAndPlay(mediaItems.getOrNull(safeIndex))
            future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, safeIndex, startPositionMs))
        }
        return future
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            val target = autoResolver.mostRecent()
            if (target == null) {
                future.setException(IllegalStateException("Nothing to resume"))
            } else {
                play(target)
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        mutableListOf(target.toMediaItem()),
                        0,
                        androidx.media3.common.C.TIME_UNSET,
                    ),
                )
            }
        }
        return future
    }

    /** Resolve a requested item's media id / voice query and start playback.
     *  Awaits only the (fast, Room-backed) resolve; the actual load is fired
     *  async so we never block the returned future on `loadAndPlay` — Android
     *  Auto can time the future out. No-op when nothing resolves. */
    private suspend fun resolveAndPlay(item: MediaItem?) {
        item ?: return
        val mediaId = item.mediaId.takeIf { it.isNotEmpty() }
        val query = item.requestMetadata.searchQuery
        val target = autoResolver.resolve(mediaId, query) ?: return
        play(target)
    }

    /** Fire-and-forget playback start; intentionally not awaited (see
     *  [resolveAndPlay]). EnginePlayer then reports its own timeline. */
    private fun play(target: PlayTarget) {
        scope.launch {
            controller.play(target.fictionId, target.chapterId, target.charOffset)
        }
    }
}

/** Protocol-echo MediaItem carrying the canonical Auto media id for a target. */
private fun PlayTarget.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(AutoMediaId.chapter(AutoMediaId.RECENT, fictionId, chapterId))
        .build()
