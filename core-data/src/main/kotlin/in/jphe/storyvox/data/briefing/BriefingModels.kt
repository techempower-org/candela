package `in`.jphe.storyvox.data.briefing

import `in`.jphe.storyvox.data.source.SourceIds
import kotlinx.serialization.Serializable

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
@Serializable
data class SourceQuota(
    val sourceId: String,
    val count: Int,
    /** Picker toggle (#1467 slice B). Off keeps [count] so re-enabling restores it. */
    val enabled: Boolean = true,
)

/**
 * Which sources feed the briefing and how many items each. In slice 1 this is
 * hardcoded to [DEFAULT]; the slice-2 config picker (#1467 follow-up) persists
 * a user-chosen value and swaps it in **without touching the builder** — the
 * builder consumes a `BriefingConfig`, not a hardcoded list.
 */
@Serializable
data class BriefingConfig(
    val sources: List<SourceQuota>,
    /** Hard cap on the assembled episode length, across all sources. */
    val maxItems: Int = DEFAULT_MAX_ITEMS,
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

        /** Upper bound on one episode; the picker's per-source steppers can't exceed it in sum. */
        const val DEFAULT_MAX_ITEMS = 30
    }
}

/**
 * A resolved, immediately-playable briefing entry — a concrete
 * `(fictionId, chapterId)` the [PlaybackController][in.jphe.storyvox] can load,
 * plus the display metadata the queue UI shows. Built by
 * [BriefingBuilder.build] from each source's latest items.
 */
@Serializable
data class BriefingItem(
    val fictionId: String,
    val chapterId: String,
    val sourceId: String,
    val title: String,
)

/**
 * Pseudo-source ids for briefing inputs that are not a `FictionSource` browse
 * listing (#1467 slice B). They are namespaced with a `briefing:` prefix so
 * they can never collide with a real `@SourcePlugin` id.
 */
object BriefingSources {
    /** Unread new-chapter events from the cross-source Inbox (#383). */
    const val INBOX: String = "briefing:inbox"

    /** Device calendar (#1495) — its first chapter is today's agenda. */
    const val CALENDAR: String = "calendar"

    /**
     * Everything the picker offers, in the order the episode plays. Each entry
     * reuses an existing source's fetch path — no briefing-specific fetchers.
     */
    val CATALOG: List<SourceQuota> = listOf(
        SourceQuota(SourceIds.GOOGLE_NEWS, count = 3, enabled = false),
        SourceQuota(SourceIds.HACKERNEWS, count = 3),
        SourceQuota(SourceIds.ARXIV, count = 3),
        SourceQuota(SourceIds.RSS, count = 3),
        SourceQuota(SourceIds.GITHUB, count = 3),
        SourceQuota(INBOX, count = 5, enabled = false),
        SourceQuota(CALENDAR, count = 1, enabled = false),
    )
}

/** When the daily prebuild runs (local time). Off by default — opt-in. */
@Serializable
data class BriefingSchedule(
    val enabled: Boolean = false,
    val hour: Int = 6,
    val minute: Int = 30,
)

/** Everything the briefing screen persists. */
@Serializable
data class BriefingSettings(
    val config: BriefingConfig = BriefingPlanner.normalize(BriefingConfig.DEFAULT),
    val schedule: BriefingSchedule = BriefingSchedule(),
)

/**
 * A queue assembled ahead of time by the daily prebuild worker. Valid only for
 * the local [epochDay] it was built on and the exact [configKey] it was built
 * from — change the picker and the next play rebuilds live.
 */
@Serializable
data class PrebuiltBriefing(
    val epochDay: Long,
    val configKey: String,
    val builtAtMillis: Long,
    val items: List<BriefingItem>,
)
