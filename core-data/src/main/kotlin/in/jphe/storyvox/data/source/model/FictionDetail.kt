package `in`.jphe.storyvox.data.source.model

/**
 * Fully-populated fiction record returned by [FictionSource.fictionDetail].
 *
 * `chapters` is the full table-of-contents at fetch time — bodies are NOT included,
 * the caller must fetch each chapter separately (typically through a repository
 * which schedules `ChapterDownloadWorker`).
 *
 * Exception: [prefetchedBodies]. Issue #1497 — sources that parse every item's
 * body while building the TOC (RSS/Atom feeds) can hand those bodies back here
 * so the repository persists them at refresh time and taps never re-fetch.
 */
data class FictionDetail(
    val summary: FictionSummary,
    val chapters: List<ChapterInfo>,
    val genres: List<String> = emptyList(),
    val wordCount: Long? = null,
    val views: Long? = null,
    val followers: Int? = null,
    val lastUpdatedAt: Long? = null,
    val authorId: String? = null,
    /**
     * Issue #1497 — chapter bodies the source already parsed at
     * detail-fetch time, keyed by [ChapterInfo.id]. Empty for the common
     * case (sources that fetch bodies lazily on tap). When a chapter's id
     * appears here, [FictionRepository.refreshDetail] writes the body into
     * the chapter row and marks it DOWNLOADED, so the reader/playback layer
     * reads from Room without a network round-trip. Only meaningful on the
     * source→repository ingestion path — the DB-backed read path
     * (`observeFiction`) leaves it empty.
     */
    val prefetchedBodies: Map<String, ChapterBody> = emptyMap(),
)
