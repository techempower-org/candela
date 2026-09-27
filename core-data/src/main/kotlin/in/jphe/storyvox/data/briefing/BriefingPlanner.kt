package `in`.jphe.storyvox.data.briefing

import `in`.jphe.storyvox.data.db.entity.InboxEvent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure playlist-assembly rules for the morning briefing (#1467 slice B).
 *
 * Everything that decides *what* the episode contains and *when* it is
 * prebuilt lives here, free of Android, Room and coroutines, so it is covered
 * by plain JVM tests. [DefaultBriefingBuilder] only fetches candidates and
 * hands them to [assemble].
 */
object BriefingPlanner {

    /**
     * Merge a stored [config] with [BriefingSources.CATALOG] so the picker
     * always shows every offerable source: catalog entries appear in catalog
     * order carrying the user's saved count/toggle when present (else the
     * catalog default); ids the user saved that the catalog no longer lists are
     * kept at the end rather than silently dropped.
     */
    fun normalize(config: BriefingConfig): BriefingConfig {
        val saved = config.sources.associateBy { it.sourceId }
        val catalogIds = BriefingSources.CATALOG.map { it.sourceId }.toSet()
        val merged = BriefingSources.CATALOG.map { entry -> saved[entry.sourceId] ?: entry } +
            config.sources.filter { it.sourceId !in catalogIds }
        return config.copy(
            sources = merged.map { it.copy(count = it.count.coerceIn(MIN_COUNT, MAX_COUNT)) },
            maxItems = config.maxItems.coerceIn(1, BriefingConfig.DEFAULT_MAX_ITEMS),
        )
    }

    /** The quotas that actually contribute: enabled with a positive count, in config order. */
    fun activeQuotas(config: BriefingConfig): List<SourceQuota> =
        config.sources.filter { it.enabled && it.count > 0 }

    /**
     * Assemble the episode from per-source [candidates] (each list already in
     * that source's "latest first" order).
     *
     * Rules, in order:
     *  1. Sources play in config order; disabled / zero-count quotas are skipped.
     *  2. Each source contributes up to its `count` items.
     *  3. An item already queued by an earlier source (same fiction + chapter —
     *     e.g. an Inbox new chapter that is also an RSS headline) is skipped and
     *     the source's next candidate fills its slot.
     *  4. The whole episode is capped at [BriefingConfig.maxItems].
     */
    fun assemble(
        config: BriefingConfig,
        candidates: Map<String, List<BriefingItem>>,
    ): List<BriefingItem> {
        val seen = HashSet<Pair<String, String>>()
        val out = ArrayList<BriefingItem>()
        for (quota in activeQuotas(config)) {
            var taken = 0
            for (item in candidates[quota.sourceId].orEmpty()) {
                if (taken >= quota.count || out.size >= config.maxItems) break
                if (seen.add(item.fictionId to item.chapterId)) {
                    out += item
                    taken++
                }
            }
            if (out.size >= config.maxItems) break
        }
        return out
    }

    /**
     * Inbox candidates: unread new-chapter events that deep-link to a concrete
     * chapter, most recent first, at most one per fiction (the newest), up to
     * [count]. Source-wide events (no fiction/chapter) aren't playable and are
     * dropped.
     */
    fun inboxItems(events: List<InboxEvent>, count: Int): List<BriefingItem> =
        events.asSequence()
            .filter { !it.isRead && it.fictionId != null && it.chapterId != null }
            .sortedByDescending { it.ts }
            .distinctBy { it.fictionId }
            .take(count.coerceAtLeast(0))
            .map { ev ->
                BriefingItem(
                    fictionId = ev.fictionId!!,
                    chapterId = ev.chapterId!!,
                    sourceId = BriefingSources.INBOX,
                    title = ev.body?.takeIf { it.isNotBlank() } ?: ev.title,
                )
            }
            .toList()

    /**
     * Stable fingerprint of what a config would build. Only the parts that
     * change the output count: active quotas in order + the cap. Toggling a
     * disabled source's count does not invalidate a prebuilt queue.
     */
    fun configKey(config: BriefingConfig): String =
        activeQuotas(config).joinToString(",") { "${it.sourceId}=${it.count}" } + "|max=${config.maxItems}"

    /**
     * The prebuilt queue if it's usable right now: built today (local
     * [epochDay]), from the same [configKey], and non-empty. Else null → build live.
     */
    fun freshPrebuilt(
        prebuilt: PrebuiltBriefing?,
        config: BriefingConfig,
        epochDay: Long,
    ): List<BriefingItem>? =
        prebuilt
            ?.takeIf { it.epochDay == epochDay && it.configKey == configKey(config) && it.items.isNotEmpty() }
            ?.items

    /** Local epoch-day for [nowMillis] in [zone] — the prebuilt queue's validity key. */
    fun epochDay(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Millis from [nowMillis] until the next local [hour]:[minute] in [zone]
     * — later today if that time is still ahead, else tomorrow. Always > 0.
     * DST-safe: computed on the zoned calendar, not by adding 24h.
     */
    fun nextRunDelayMillis(nowMillis: Long, zone: ZoneId, hour: Int, minute: Int): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var target: ZonedDateTime = ZonedDateTime.of(now.toLocalDate(), time, zone)
        if (!target.isAfter(now)) target = ZonedDateTime.of(now.toLocalDate().plusDays(1), time, zone)
        return target.toInstant().toEpochMilli() - nowMillis
    }

    /**
     * Items the daily prebuild should download + pre-render ahead of time.
     * The calendar's chapters are computed live from the current day, so a
     * cached render would go stale; it is always read fresh.
     */
    fun prerenderTargets(items: List<BriefingItem>): List<BriefingItem> =
        items.filter { it.sourceId != BriefingSources.CALENDAR }

    /** Picker stepper bounds per source. */
    const val MIN_COUNT = 1
    const val MAX_COUNT = 10
}
