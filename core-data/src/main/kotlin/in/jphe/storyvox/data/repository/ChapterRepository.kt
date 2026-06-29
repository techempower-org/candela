package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterSearchRow
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write surface over chapters — both metadata (table-of-contents rows)
 * and bodies (downloaded HTML/plain text). Bodies arrive via
 * [ChapterDownloadWorker]; this repo schedules the work.
 */
interface ChapterRepository {

    fun observeChapters(fictionId: String): Flow<List<ChapterInfo>>

    /** Returns null until the body has been downloaded. */
    fun observeChapter(chapterId: String): Flow<ChapterContent?>

    fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>>

    /**
     * Issue #282 — observable set of chapter ids the user has marked
     * played for this fiction. The UI combines this with [observeChapters]
     * to render the `isFinished` indicator on each chapter row. Distinct
     * from [observeDownloadState] because download-state and play-state
     * are independent axes (a chapter may be downloaded but unplayed,
     * or played without being downloaded if streamed live).
     */
    fun observePlayedChapterIds(fictionId: String): Flow<Set<String>>

    /**
     * Issue #1189 — observable map of chapterId → cleaned content preview
     * (~100 chars of opening prose) for chapters that have a cached body.
     * The UI combines this with [observeChapters] to show a snippet under
     * each chapter title, so listeners can orient when a source numbers its
     * chapters generically ("Chapter 1", "Chapter 2", …).
     *
     * Only chapters with a cached body (downloaded / read / pre-rendered)
     * appear; the map grows as the user reads or downloads. A chapter absent
     * from the map simply renders without a preview line.
     */
    fun observeChapterPreviews(fictionId: String): Flow<Map<String, String>>

    /** Schedule a single chapter download via WorkManager. */
    suspend fun queueChapterDownload(
        fictionId: String,
        chapterId: String,
        requireUnmetered: Boolean = true,
    )

    /** Schedule downloads for every not-yet-downloaded chapter (eager mode). */
    suspend fun queueAllMissing(
        fictionId: String,
        requireUnmetered: Boolean = true,
    )

    suspend fun markRead(chapterId: String, read: Boolean = true)

    /** Courtesy alias for the playback layer. Same effect as `markRead(true)`. */
    suspend fun markChapterPlayed(chapterId: String)

    /** Drop body bytes for chapters older than [keepLast]; keeps metadata rows. */
    suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int)

    // ─── playback-layer accessors ─────────────────────────────────────────

    /**
     * Joined "everything the player needs to start a track" lookup —
     * chapter text + title + parent book's title + cover URL in one row.
     * Returns `null` when either the chapter row doesn't exist or its body
     * hasn't been downloaded yet.
     */
    suspend fun getChapter(id: String): PlaybackChapter?

    /** ID of the next chapter in reading order, or null at the end of the book. */
    suspend fun getNextChapterId(currentChapterId: String): String?

    /** ID of the previous chapter in reading order, or null at the start. */
    suspend fun getPreviousChapterId(currentChapterId: String): String?

    /**
     * Issue #293 — debug-surface storage diagnostic. Returns count of
     * chapters with a cached body + estimated byte usage. Single
     * SQL aggregate; safe to call on a poll cadence (~10s on the debug
     * screen). Bytes are an upper-bound estimate (char count × 2);
     * exact UTF-8 byte cost would require LENGTH(CAST(plainBody AS BLOB))
     * which is more expensive on a large table for no practical gain
     * on the storage diagnostic.
     */
    suspend fun cachedBodyUsage(): CachedBodyUsage

    /**
     * Issue #121 — set or clear the per-chapter bookmark. Passing null
     * clears. The bookmark is a char-offset into the chapter's plainBody,
     * mirroring how the player addresses positions throughout the
     * pipeline (no time-based offset that would shift with speed).
     */
    suspend fun setChapterBookmark(chapterId: String, charOffset: Int?)

    /** Issue #121 — read the persisted bookmark for a chapter, or null. */
    suspend fun chapterBookmark(chapterId: String): Int?

    /**
     * Issue #1229 — in-book text search. Returns the downloaded chapters of
     * [fictionId] whose body contains [query] (case-insensitive substring),
     * in reading order, each with its full plain-text body so the caller can
     * extract exact match offsets + a highlighted snippet. Capped at [limit]
     * chapters to bound memory on a match-everything query; the caller treats
     * a full result list as "possibly truncated". A blank [query] returns
     * empty without touching the DB.
     */
    suspend fun searchChapterBodies(
        fictionId: String,
        query: String,
        limit: Int = DEFAULT_BOOK_SEARCH_LIMIT,
    ): List<ChapterSearchRow>
}

/** Issue #1229 — default chapter cap for [ChapterRepository.searchChapterBodies]. */
const val DEFAULT_BOOK_SEARCH_LIMIT = 500

/** Issue #293 — paired count/bytes return for [ChapterRepository.cachedBodyUsage]. */
data class CachedBodyUsage(
    val count: Int,
    val bytesEstimate: Long,
)

@Singleton
class ChapterRepositoryImpl @Inject constructor(
    private val dao: ChapterDao,
    private val scheduler: ChapterDownloadScheduler,
) : ChapterRepository {

    override fun observeChapters(fictionId: String): Flow<List<ChapterInfo>> =
        dao.observeChapterInfosByFiction(fictionId).map { rows -> rows.map(::toInfo) }

    override fun observeChapter(chapterId: String): Flow<ChapterContent?> =
        dao.observe(chapterId).map { row ->
            // Issue #373 — audio chapters (audioUrl != null) round-trip
            // through here with empty bodies; treat as "available" so the
            // reader / playback layer can pick the audio path. Pure
            // text chapters still require both bodies to be present.
            if (row == null) null
            else if (row.audioUrl != null) ChapterContent(
                info = row.toInfo(),
                htmlBody = row.htmlBody.orEmpty(),
                plainBody = row.plainBody.orEmpty(),
                notesAuthor = row.notesAuthor,
                notesAuthorPosition = row.notesAuthorPosition,
                audioUrl = row.audioUrl,
            )
            else if (row.htmlBody == null || row.plainBody == null) null
            else ChapterContent(
                info = row.toInfo(),
                htmlBody = row.htmlBody,
                plainBody = row.plainBody,
                notesAuthor = row.notesAuthor,
                notesAuthorPosition = row.notesAuthorPosition,
            )
        }

    override fun observePlayedChapterIds(fictionId: String): Flow<Set<String>> =
        dao.observePlayedChapterIds(fictionId).map { it.toSet() }

    override fun observeChapterPreviews(fictionId: String): Flow<Map<String, String>> =
        dao.observeChapterPreviews(fictionId)
            .map { rows ->
                rows.mapNotNull { row ->
                    chapterPreviewText(row.preview)?.let { row.id to it }
                }.toMap()
            }
            // Room invalidates this query on any write to the `chapter`
            // table (read-state toggles, download-state flips), but the
            // preview content only changes when a body is (re)written —
            // dedupe so an unrelated row write doesn't re-push an identical
            // map down the combine in [chaptersFor].
            .distinctUntilChanged()
            // Issue #1220 — chapterPreviewText (heavy regex + HTML-decode)
            // runs over every chapter in the .map above; without flowOn it
            // executes on the collector (Main) thread and janks large
            // chapter lists. Move the whole upstream chain — the map and the
            // distinctUntilChanged comparison — onto Default.
            .flowOn(Dispatchers.Default)
            .flowOn(Dispatchers.Default)

    override fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>> =
        dao.observeDownloadStates(fictionId).map { rows ->
            rows.associate { it.id to it.downloadState }
        }

    override suspend fun queueChapterDownload(
        fictionId: String,
        chapterId: String,
        requireUnmetered: Boolean,
    ) {
        // Mark QUEUED *before* dispatch so observers see the pending state
        // immediately, even if the scheduler synchronously fast-paths.
        dao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, System.currentTimeMillis(), null)
        scheduler.schedule(fictionId, chapterId, requireUnmetered)
    }

    override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) {
        val missing = dao.missingForFiction(fictionId)
        for (chapter in missing) {
            queueChapterDownload(fictionId, chapter.id, requireUnmetered)
        }
    }

    override suspend fun markRead(chapterId: String, read: Boolean) {
        dao.setRead(chapterId, read, System.currentTimeMillis())
    }

    override suspend fun markChapterPlayed(chapterId: String) = markRead(chapterId, true)

    override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) {
        dao.trimDownloadedBodies(fictionId, keepLast)
    }

    override suspend fun getChapter(id: String): PlaybackChapter? =
        dao.playbackChapter(id)?.let {
            // Issue #373 — audio chapters (audioUrl != null) come back
            // with empty text and the player routes through Media3
            // instead of TTS. Without this branch the empty-text guard
            // below would null them out and the player would render
            // "Chapter not ready" for a chapter that's perfectly
            // playable as a stream. Order matters: check audioUrl first
            // so a future audio source that ALSO carries a transcript
            // (e.g. LibriVox + Internet Archive) doesn't trip the
            // text-required guard.
            val audioUrl = it.audioUrl
            if (audioUrl != null) PlaybackChapter(
                id = it.id,
                fictionId = it.fictionId,
                text = it.text, // may be empty for pure-audio chapters
                title = it.title,
                bookTitle = it.bookTitle,
                coverUrl = it.coverUrl,
                audioUrl = audioUrl,
            )
            // The DAO uses COALESCE on plainBody → '', so an undownloaded
            // chapter comes back with empty text. Treat that as "not yet
            // available" so the player doesn't try to speak silence.
            else if (it.text.isEmpty()) null
            else PlaybackChapter(
                id = it.id,
                fictionId = it.fictionId,
                text = it.text,
                title = it.title,
                bookTitle = it.bookTitle,
                coverUrl = it.coverUrl,
            )
        }

    override suspend fun getNextChapterId(currentChapterId: String): String? =
        dao.nextChapterId(currentChapterId)

    override suspend fun getPreviousChapterId(currentChapterId: String): String? =
        dao.previousChapterId(currentChapterId)

    override suspend fun cachedBodyUsage(): CachedBodyUsage {
        val row = dao.cacheUsage()
        return CachedBodyUsage(count = row.count, bytesEstimate = row.bytes)
    }

    override suspend fun setChapterBookmark(chapterId: String, charOffset: Int?) {
        dao.setBookmark(chapterId, charOffset)
    }

    override suspend fun chapterBookmark(chapterId: String): Int? =
        dao.getBookmark(chapterId)

    override suspend fun searchChapterBodies(
        fictionId: String,
        query: String,
        limit: Int,
    ): List<ChapterSearchRow> {
        // Trim so a stray paste space doesn't zero the LIKE pre-filter; the
        // feature layer's findMatches trims the same way, keeping the coarse
        // SQL filter and the exact literal re-scan in agreement. Room runs
        // this suspend query off the main thread on its own executor.
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return dao.searchChapters(fictionId, needle, limit)
    }
}
