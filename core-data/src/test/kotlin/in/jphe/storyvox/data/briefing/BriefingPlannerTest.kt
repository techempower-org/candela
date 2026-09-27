package `in`.jphe.storyvox.data.briefing

import `in`.jphe.storyvox.data.db.entity.InboxEvent
import `in`.jphe.storyvox.data.source.SourceIds
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1467 slice B — pure playlist-assembly and scheduling rules. */
class BriefingPlannerTest {

    private fun item(source: String, n: Int) = BriefingItem("$source:$n", "$source:$n:c", source, "$source #$n")

    private fun items(source: String, vararg ns: Int) = ns.map { item(source, it) }

    // ─── assemble ────────────────────────────────────────────────────────────

    @Test fun `assemble keeps config order and per-source counts`() {
        val config = BriefingConfig(listOf(SourceQuota("b", 1), SourceQuota("a", 2)))
        val out = BriefingPlanner.assemble(config, mapOf("a" to items("a", 1, 2, 3), "b" to items("b", 1, 2)))
        assertEquals(listOf("b:1", "a:1", "a:2"), out.map { it.fictionId })
    }

    @Test fun `assemble skips disabled and zero-count quotas`() {
        val config = BriefingConfig(
            listOf(SourceQuota("a", 2, enabled = false), SourceQuota("b", 0), SourceQuota("c", 1)),
        )
        val out = BriefingPlanner.assemble(
            config,
            mapOf("a" to items("a", 1), "b" to items("b", 1), "c" to items("c", 1)),
        )
        assertEquals(listOf("c:1"), out.map { it.fictionId })
    }

    @Test fun `assemble dedupes across sources and backfills the slot`() {
        val shared = item("x", 1)
        val config = BriefingConfig(listOf(SourceQuota("a", 1), SourceQuota("b", 2)))
        val out = BriefingPlanner.assemble(
            config,
            mapOf("a" to listOf(shared), "b" to listOf(shared.copy(sourceId = "b")) + items("b", 2, 3)),
        )
        assertEquals(listOf("x:1", "b:2", "b:3"), out.map { it.fictionId })
    }

    @Test fun `assemble caps the whole episode at maxItems`() {
        val config = BriefingConfig(listOf(SourceQuota("a", 3), SourceQuota("b", 3)), maxItems = 4)
        val out = BriefingPlanner.assemble(config, mapOf("a" to items("a", 1, 2, 3), "b" to items("b", 1, 2, 3)))
        assertEquals(listOf("a:1", "a:2", "a:3", "b:1"), out.map { it.fictionId })
    }

    @Test fun `assemble tolerates a source with no candidates`() {
        val config = BriefingConfig(listOf(SourceQuota("missing", 3), SourceQuota("a", 1)))
        assertEquals(listOf("a:1"), BriefingPlanner.assemble(config, mapOf("a" to items("a", 1))).map { it.fictionId })
    }

    // ─── inbox ───────────────────────────────────────────────────────────────

    private fun event(id: Long, fiction: String?, chapter: String?, ts: Long, read: Boolean = false, body: String? = null) =
        InboxEvent(
            id = id, sourceId = "royalroad", fictionId = fiction, chapterId = chapter,
            title = "new in $fiction", body = body, ts = ts, isRead = read, deepLinkUri = null,
        )

    @Test fun `inbox items are unread playable newest-first one per fiction`() {
        val out = BriefingPlanner.inboxItems(
            listOf(
                event(1, "f1", "f1c1", ts = 10),
                event(2, "f1", "f1c2", ts = 30, body = "Chapter 2"), // newer, same fiction wins
                event(3, "f2", "f2c1", ts = 20, read = true), // read → skipped
                event(4, null, null, ts = 40), // source-wide → not playable
                event(5, "f3", "f3c1", ts = 25),
            ),
            count = 5,
        )
        assertEquals(listOf("f1c2", "f3c1"), out.map { it.chapterId })
        assertEquals("Chapter 2", out.first().title)
        assertTrue(out.all { it.sourceId == BriefingSources.INBOX })
    }

    @Test fun `inbox items honour the count`() {
        val out = BriefingPlanner.inboxItems((1L..5L).map { event(it, "f$it", "c$it", ts = it) }, count = 2)
        assertEquals(listOf("c5", "c4"), out.map { it.chapterId })
    }

    // ─── normalize ───────────────────────────────────────────────────────────

    @Test fun `normalize shows every catalog source and keeps saved choices`() {
        val saved = BriefingConfig(listOf(SourceQuota(SourceIds.ARXIV, 7, enabled = false), SourceQuota("legacy", 2)))
        val out = BriefingPlanner.normalize(saved)
        assertEquals(
            BriefingSources.CATALOG.map { it.sourceId } + "legacy",
            out.sources.map { it.sourceId },
        )
        val arxiv = out.sources.first { it.sourceId == SourceIds.ARXIV }
        assertEquals(7, arxiv.count)
        assertFalse(arxiv.enabled)
        // Unsaved catalog entries carry their catalog default.
        assertFalse(out.sources.first { it.sourceId == BriefingSources.INBOX }.enabled)
    }

    @Test fun `normalize clamps counts and the cap`() {
        val out = BriefingPlanner.normalize(BriefingConfig(listOf(SourceQuota(SourceIds.RSS, 99)), maxItems = 500))
        assertEquals(BriefingPlanner.MAX_COUNT, out.sources.first { it.sourceId == SourceIds.RSS }.count)
        assertEquals(BriefingConfig.DEFAULT_MAX_ITEMS, out.maxItems)
    }

    @Test fun `the default settings play the original four sources`() {
        val active = BriefingPlanner.activeQuotas(BriefingSettings().config).map { it.sourceId }
        assertEquals(listOf(SourceIds.HACKERNEWS, SourceIds.ARXIV, SourceIds.RSS, SourceIds.GITHUB), active)
    }

    // ─── prebuilt freshness ──────────────────────────────────────────────────

    private val cfg = BriefingConfig(listOf(SourceQuota("a", 2)))

    private fun prebuilt(day: Long, config: BriefingConfig = cfg, list: List<BriefingItem> = items("a", 1)) =
        PrebuiltBriefing(epochDay = day, configKey = BriefingPlanner.configKey(config), builtAtMillis = 0, items = list)

    @Test fun `a prebuilt queue from today with the same config is used`() {
        assertEquals(items("a", 1), BriefingPlanner.freshPrebuilt(prebuilt(100), cfg, epochDay = 100))
    }

    @Test fun `a stale, reconfigured, empty or missing prebuilt queue is ignored`() {
        assertNull(BriefingPlanner.freshPrebuilt(prebuilt(99), cfg, epochDay = 100))
        assertNull(BriefingPlanner.freshPrebuilt(prebuilt(100, config = BriefingConfig(listOf(SourceQuota("a", 3)))), cfg, 100))
        assertNull(BriefingPlanner.freshPrebuilt(prebuilt(100, list = emptyList()), cfg, 100))
        assertNull(BriefingPlanner.freshPrebuilt(null, cfg, 100))
    }

    @Test fun `configKey ignores disabled sources`() {
        val withOff = BriefingConfig(listOf(SourceQuota("a", 2), SourceQuota("b", 9, enabled = false)))
        assertEquals(BriefingPlanner.configKey(cfg), BriefingPlanner.configKey(withOff))
    }

    // ─── scheduling ──────────────────────────────────────────────────────────

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun `next run is later today when the time is still ahead`() {
        val now = millis(2026, 9, 27, 5, 0)
        assertEquals(90 * 60_000L, BriefingPlanner.nextRunDelayMillis(now, zone, 6, 30))
    }

    @Test fun `next run rolls to tomorrow once the time has passed or is now`() {
        val now = millis(2026, 9, 27, 6, 30)
        assertEquals(24 * 3_600_000L, BriefingPlanner.nextRunDelayMillis(now, zone, 6, 30))
    }

    @Test fun `next run across a DST change lands on the wall-clock time`() {
        // US DST ends 2026-11-01 02:00 → the night is 25 hours long.
        val now = millis(2026, 10, 31, 6, 30)
        assertEquals(25 * 3_600_000L, BriefingPlanner.nextRunDelayMillis(now, zone, 6, 30))
    }

    @Test fun `epochDay follows the local calendar`() {
        val lateNight = millis(2026, 9, 27, 23, 59)
        val nextMorning = millis(2026, 9, 28, 0, 1)
        assertEquals(BriefingPlanner.epochDay(lateNight, zone) + 1, BriefingPlanner.epochDay(nextMorning, zone))
    }
}
