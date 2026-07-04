package `in`.jphe.storyvox.data.repository.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1463 — the encoded payload is what the preview shows AND what the share sheet
 * sends, byte-for-byte, so its exact bytes are pinned here. Any drift is a behavioural
 * change (the user's preview would no longer match reality) and must be intentional.
 */
class ImpactReportJsonTest {

    @Test
    fun `encodes canonical pretty json with fixed key order`() {
        val report = ImpactReport(
            schema = 1,
            period = "2026-07",
            appVersion = "1.9",
            hoursListenedBucket = 15,
            chaptersCompletedBucket = 20,
            booksCompletedBucket = 5,
            sourcesUsed = listOf("gutenberg", "royalroad", "wikipedia"),
        )
        val expected = listOf(
            "{",
            "  \"schema\": 1,",
            "  \"period\": \"2026-07\",",
            "  \"app_version\": \"1.9\",",
            "  \"hours_listened_bucket\": 15,",
            "  \"chapters_completed_bucket\": 20,",
            "  \"books_completed_bucket\": 5,",
            "  \"sources_used\": [\"gutenberg\", \"royalroad\", \"wikipedia\"]",
            "}",
        ).joinToString("\n")
        assertEquals(expected, ImpactReportJson.encode(report))
    }

    @Test
    fun `empty sources render as an empty array`() {
        val report = ImpactReport(
            schema = 1,
            period = "2026-07",
            appVersion = "1.9",
            hoursListenedBucket = 0,
            chaptersCompletedBucket = 0,
            booksCompletedBucket = 0,
            sourcesUsed = emptyList(),
        )
        assertTrue(ImpactReportJson.encode(report).contains("\"sources_used\": []"))
    }

    @Test
    fun `encode is deterministic across calls`() {
        val report = ImpactReport(1, "2026-07", "1.9", 5, 10, 1, listOf("ao3"))
        assertEquals(ImpactReportJson.encode(report), ImpactReportJson.encode(report))
    }
}
