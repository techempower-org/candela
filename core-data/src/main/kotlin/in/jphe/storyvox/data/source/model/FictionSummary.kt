package `in`.jphe.storyvox.data.source.model

/**
 * Lightweight representation of a fiction used in lists (browse, search, follows).
 *
 * Sources may leave optional fields null when the listing page doesn't expose them;
 * the gap is filled later by [FictionDetail] from a detail-page fetch.
 */
data class FictionSummary(
    val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val status: FictionStatus = FictionStatus.ONGOING,
    val chapterCount: Int? = null,
    val rating: Float? = null,
    /** Issue #211 — true when storyvox has pushed a follow to the
     *  source's account (e.g. Royal Road's `/fictions/setbookmark`)
     *  or the periodic pull observed the user follows this fiction.
     *  Defaults to false; only meaningful for sources with an
     *  account-side follow concept (RR today). */
    val followedRemotely: Boolean = false,
    /** Issue #382 — populated from `FictionSource.supportsFollow` at
     *  the repository mapper layer. Drives the FictionDetail Follow
     *  button's visibility on the UI side without each consumer
     *  needing to know which sources happen to support follow. */
    val supportsFollow: Boolean = false,
    /** Issue #793 — wall-clock time the row joined the user's library
     *  (`fiction.addedToLibraryAt`). Null on rows the user hasn't
     *  added (browse / search listings). Drives the Library "recently
     *  added" / "longest unread" sort modes. */
    val addedAt: Long? = null,
    /** Issue #793 — wall-clock time the most-recent playback-position
     *  upsert for this fiction. Null when the user has never started
     *  the book. Populated only at the Library-sort join site; null
     *  on browse / search summaries. Drives "recently played" +
     *  "longest unread" sort modes. */
    val lastPlayedAt: Long? = null,
)
