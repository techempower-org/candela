package `in`.jphe.storyvox.data.source.model

/**
 * The body of a chapter as returned by the source.
 *
 * Implementations should hand back BOTH a sanitized HTML body (for the reader view)
 * and a plaintext rendering (for TTS). It is acceptable for `plainBody` to be a
 * naive strip of `htmlBody` — the playback layer applies further normalization
 * (sentence segmentation, abbreviation expansion) downstream.
 *
 * Issue #373 — Audio-stream backend category. When [audioUrl] is non-null
 * the chapter is pre-recorded / live audio rather than text-for-TTS. The
 * playback engine routes the URL through Media3 / ExoPlayer and bypasses
 * the TTS pipeline entirely. `htmlBody` / `plainBody` may be empty for
 * pure-audio chapters; the reader view falls back to a "Live audio" /
 * track-info card in that case (downstream wiring in `:feature`).
 *
 * KVMR (issue #374) is the first concrete audio source — single live
 * stream URL, no plaintext body. Future audio backends (LibriVox,
 * Internet Archive) will populate `audioUrl` alongside chapter-level
 * metadata that maps to MediaSession's title / artwork without needing
 * a TTS text body.
 */
data class ChapterContent(
    val info: ChapterInfo,
    val htmlBody: String,
    val plainBody: String,
    val notesAuthor: String? = null,
    val notesAuthorPosition: NotePosition? = null,
    /**
     * Issue #373 — when non-null, the playback layer treats this chapter
     * as a Media3-routed audio source (live stream or pre-recorded
     * audiobook track). Null preserves the existing text→TTS pipeline
     * for every backend that doesn't surface audio.
     */
    val audioUrl: String? = null,
)

/**
 * Issue #1497 — a chapter body a source already has in hand at
 * detail-fetch time. Some backends (RSS/Atom feeds) parse every item's
 * full content while building the table-of-contents, so there's no
 * reason to re-fetch it on tap. A source surfaces these via
 * [FictionDetail.prefetchedBodies] (keyed by [ChapterInfo.id]); the
 * repository persists them into the existing offline chapter store
 * during refresh and marks the row DOWNLOADED, so a chapter tap reads
 * from Room instead of round-tripping the network.
 *
 * Distinct from [ChapterContent] (which pairs a body with its
 * [ChapterInfo] for the on-demand fetch path): this carries only the
 * body bytes, correlated to a chapter by the map key, and coordinates
 * with the existing download pipeline rather than adding a parallel
 * store (see #1314).
 */
data class ChapterBody(
    val htmlBody: String,
    val plainBody: String,
    val notesAuthor: String? = null,
    val notesAuthorPosition: NotePosition? = null,
    val audioUrl: String? = null,
)

/** Whether an author's-note block sits before or after the main body. */
enum class NotePosition { BEFORE, AFTER }

/** Lifecycle state of a fiction on its source site. */
enum class FictionStatus { ONGOING, COMPLETED, HIATUS, STUB, DROPPED }
