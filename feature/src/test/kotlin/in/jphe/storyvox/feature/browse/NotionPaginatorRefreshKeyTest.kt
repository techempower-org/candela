package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Issue #1556 — pins the "Notion-only paginator keying" invariant behind
 * the auto-refresh-on-connect fix. The Notion config fingerprint must feed
 * the paginator key ONLY for the Notion source, so:
 *  - connecting / editing Notion rebuilds the Notion paginator (auto-fetch),
 *  - and NO other source's paginator rebuilds on unrelated settings changes
 *    (which would reset its scroll/pagination).
 * Pure function, no ViewModel/Hilt needed — same posture as the favorites
 * ordering test in this package.
 */
class NotionPaginatorRefreshKeyTest {

    @Test fun `notion source carries the config fingerprint`() {
        assertEquals("true|db123", notionPaginatorRefreshKey(SourceIds.NOTION_PAT, "true|db123"))
    }

    @Test fun `non-notion sources never carry the fingerprint`() {
        for (id in listOf("rr", "github", "ao3", "gutenberg", SourceIds.ROYAL_ROAD)) {
            assertEquals("", notionPaginatorRefreshKey(id, "true|db123"))
        }
    }

    @Test fun `connect transition changes the notion key`() {
        // unconfigured → configured must produce a different key so the
        // paginator's distinctUntilChanged rebuilds it (→ auto-fetch).
        val before = notionPaginatorRefreshKey(SourceIds.NOTION_PAT, "false|")
        val after = notionPaginatorRefreshKey(SourceIds.NOTION_PAT, "true|db123")
        assertNotEquals(before, after)
    }

    @Test fun `unrelated settings change does not move a non-notion key`() {
        // Even if the Notion fingerprint flips, a non-Notion source's key
        // stays "" → its paginator is NOT rebuilt.
        val a = notionPaginatorRefreshKey("rr", "false|")
        val b = notionPaginatorRefreshKey("rr", "true|db123")
        assertEquals(a, b)
        assertEquals("", a)
    }
}
