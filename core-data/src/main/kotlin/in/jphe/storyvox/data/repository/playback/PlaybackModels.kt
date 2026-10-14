package `in`.jphe.storyvox.data.repository.playback

/**
 * Denormalized projections for the `:core-playback` layer.
 *
 * These are NOT the canonical data models — those live in
 * `data.source.model` (network-side) and `data.db.entity` (storage-side).
 * The playback layer asks for "everything Auto/Wear/notification needs in
 * one row" because it can't afford a second query while answering an Auto
 * onPlayFromMediaId or building a now-playing notification on the wire.
 *
 * Each type is a flat record carrying the strings/ids the player binds to
 * directly. Anything richer (genres, full chapter HTML) belongs on the
 * canonical types.
 */

/** Chapter snapshot the player needs to load up: text, title, parent fiction's title + cover. */
data class PlaybackChapter(
    val id: String,
    val fictionId: String,
    val text: String,
    val title: String,
    val bookTitle: String,
    val coverUrl: String?,
    /**
     * Issue #373 — Media3-routable audio source URL. When non-null the
     * player skips the TTS pipeline entirely and hands [audioUrl] to a
     * Media3 ExoPlayer (live stream / pre-recorded audiobook track).
     * Null preserves the existing text→TTS path.
     */
    val audioUrl: String? = null,
)

/** Persisted resume point — what the player saves on pause/track-end. */
data class SavedPosition(
    val fictionId: String,
    val chapterId: String,
    val charOffset: Int,
    val durationEstimateMs: Long,
)

/** "Recently played" Auto/Wear menu row. */
data class RecentItem(
    val fictionId: String,
    val chapterId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val coverUrl: String?,
)

/** Library tab Auto menu row. */
data class LibraryItem(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
)

/** Follows tab Auto menu row. */
data class FollowItem(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
)

/** Unread chapters Auto menu row — used for "Continue subscribed series". */
data class UnreadChapter(
    val fictionId: String,
    val chapterId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val coverUrl: String?,
    /** #1808 — start of the stored body text, or null when the body isn't fetched yet. */
    val bodyPreview: String? = null,
)
