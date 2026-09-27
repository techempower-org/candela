package `in`.jphe.storyvox.feature.feed

import androidx.compose.runtime.Immutable
import `in`.jphe.storyvox.data.briefing.BriefingItem
import `in`.jphe.storyvox.data.repository.playback.UnreadChapter

/**
 * #1675 — one card in the listening feed: a single unread item (an RSS post,
 * a new chapter, an HN story…) from something the user follows.
 */
@Immutable
data class FeedRow(
    val fictionId: String,
    val chapterId: String,
    /** The item's own title — the post / chapter headline. */
    val title: String,
    /** The site / feed / book it came from. */
    val publisher: String,
    val coverUrl: String?,
    val sourceId: String,
    /** Human label for [sourceId] ("RSS", "Hacker News"); falls back to the id. */
    val sourceLabel: String,
)

/**
 * Pure feed assembly for #1675, kept free of Android types so it is unit-tested
 * on the JVM without a player or a database.
 */
internal object FeedLogic {

    /** How many unread items the feed pulls from Room. */
    const val FEED_LIMIT = 100

    /**
     * Turn the reverse-chronological unread list into feed rows. Order is kept
     * as given (newest first, no ranking), duplicate chapter ids are dropped,
     * and blank chapter titles fall back to the publisher so a card never
     * renders empty.
     *
     * @param sourceIdOf maps a fiction id to its source id (production passes
     *   `FictionSourceIdResolver::resolveByShape`).
     * @param labelOf maps a source id to its display name, or null if unknown.
     */
    fun rows(
        unread: List<UnreadChapter>,
        sourceIdOf: (String) -> String,
        labelOf: (String) -> String?,
    ): List<FeedRow> {
        val seen = HashSet<String>()
        return unread.mapNotNull { u ->
            if (!seen.add(u.chapterId)) return@mapNotNull null
            val sourceId = sourceIdOf(u.fictionId)
            FeedRow(
                fictionId = u.fictionId,
                chapterId = u.chapterId,
                title = u.chapterTitle.ifBlank { u.bookTitle },
                publisher = u.bookTitle,
                coverUrl = u.coverUrl,
                sourceId = sourceId,
                sourceLabel = labelOf(sourceId)?.takeIf { it.isNotBlank() } ?: sourceId,
            )
        }
    }

    /** The playback queue for the feed: every row, in feed order. */
    fun queue(rows: List<FeedRow>): List<BriefingItem> = rows.map {
        BriefingItem(
            fictionId = it.fictionId,
            chapterId = it.chapterId,
            sourceId = it.sourceId,
            title = it.title,
        )
    }

    /**
     * Index of the row that is playing, or -1. A row counts as playing only
     * when the active queue is the feed's own queue (same chapter ids in the
     * same order), so a Morning Briefing that happens to share an item does
     * not light up a feed card.
     */
    fun playingIndex(
        rows: List<FeedRow>,
        queueChapterIds: List<String>?,
        currentChapterId: String?,
    ): Int {
        if (queueChapterIds == null || currentChapterId == null) return -1
        if (queueChapterIds != rows.map { it.chapterId }) return -1
        return rows.indexOfFirst { it.chapterId == currentChapterId }
    }
}
