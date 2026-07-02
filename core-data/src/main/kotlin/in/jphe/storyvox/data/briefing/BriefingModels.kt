package `in`.jphe.storyvox.data.briefing

import `in`.jphe.storyvox.data.source.SourceIds

/**
 * Morning-briefing domain models (#1467).
 *
 * A "briefing" is one continuous narrated queue stitched from the *latest*
 * items of several sources — HN top, arXiv, RSS feeds, GitHub activity — so the
 * user can listen to it hands-free as a single "episode". Everything here is
 * plain data with no Android / coroutine dependency, so it's trivially
 * unit-testable and safe to share across `:core-data` and `:core-playback`.
 */

/**
 * One source's contribution to a briefing: pull the [count] most-recent items
 * from [sourceId]. `sourceId` matches the stable [SourceIds] constants the
 * source-plugin registry keys on.
 */
data class SourceQuota(
    val sourceId: String,
    val count: Int,
)

/**
 * Which sources feed the briefing and how many items each. In slice 1 this is
 * hardcoded to [DEFAULT]; the slice-2 config picker (#1467 follow-up) persists
 * a user-chosen value and swaps it in **without touching the builder** — the
 * builder consumes a `BriefingConfig`, not a hardcoded list.
 */
data class BriefingConfig(
    val sources: List<SourceQuota>,
) {
    companion object {
        /**
         * The out-of-the-box briefing: the four "news-shaped" sources called
         * out in #1467, three items each. Sources the user hasn't enabled (or
         * that fail to fetch) are skipped at build time, so listing one here is
         * safe even if it's off.
         */
        val DEFAULT = BriefingConfig(
            sources = listOf(
                SourceQuota(SourceIds.HACKERNEWS, count = 3),
                SourceQuota(SourceIds.ARXIV, count = 3),
                SourceQuota(SourceIds.RSS, count = 3),
                SourceQuota(SourceIds.GITHUB, count = 3),
            ),
        )
    }
}

/**
 * A resolved, immediately-playable briefing entry — a concrete
 * `(fictionId, chapterId)` the [PlaybackController][in.jphe.storyvox] can load,
 * plus the display metadata the queue UI shows. Built by
 * [BriefingBuilder.build] from each source's latest items.
 */
data class BriefingItem(
    val fictionId: String,
    val chapterId: String,
    val sourceId: String,
    val title: String,
)
