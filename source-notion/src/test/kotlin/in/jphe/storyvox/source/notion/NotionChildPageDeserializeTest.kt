package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.source.notion.net.NotionBlock
import `in`.jphe.storyvox.source.notion.net.NotionBlockList
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1621 — prove the official-API (`GET /v1/blocks/{id}/children`) JSON
 * decodes `child_page` blocks correctly, using the SAME `Json` config
 * `NotionApi` uses (`ignoreUnknownKeys + isLenient + coerceInputValues`)
 * against the EXACT wire shape observed on JP's shorts page.
 *
 * The pre-existing [NotionChildPageChapterTest] builds [NotionBlock]
 * objects directly, so it never exercised raw-JSON → `NotionBlock`
 * deserialization for `child_page` — the untested gap that made a
 * "deserialization drops child_page" hypothesis plausible. This closes
 * it: if `type == "child_page"` survives the decode and [planChapters]
 * splits, the collapse-to-one-chapter symptom is NOT a parse bug.
 */
class NotionChildPageDeserializeTest {

    /** Byte-for-byte the config in `NotionApi` (net/NotionApi.kt). */
    private val apiJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * A realistic `blocks/{id}/children` body: two `child_page` sub-pages
     * (the shorts) + a lead paragraph + a heading_2, each carrying the
     * full complement of common Notion block fields the model does NOT
     * declare (`object`, `parent`, `created_time`, `created_by`,
     * `in_trash`, …) — exactly what `ignoreUnknownKeys` must swallow.
     * The `child_page` block matches the shape captured on-device:
     * `{type:"child_page", has_children:true, child_page:{title}}`.
     */
    private val rawChildrenResponse = """
        {
          "object": "list",
          "results": [
            {
              "object": "block",
              "id": "392a4ee6-9520-81a3-8813-c8fb40860001",
              "parent": { "type": "page_id", "page_id": "392a4ee6-9520-81a3-a813-c8fb40860572" },
              "created_time": "2024-01-01T00:00:00.000Z",
              "last_edited_time": "2024-01-02T00:00:00.000Z",
              "created_by": { "object": "user", "id": "u-1" },
              "last_edited_by": { "object": "user", "id": "u-1" },
              "has_children": false,
              "archived": false,
              "in_trash": false,
              "type": "paragraph",
              "paragraph": { "rich_text": [ { "type": "text", "text": { "content": "Lead notes." } } ] }
            },
            {
              "object": "block",
              "id": "392a4ee6-9520-8121-8fe8-ca62aa7ed899",
              "parent": { "type": "page_id", "page_id": "392a4ee6-9520-81a3-a813-c8fb40860572" },
              "created_time": "2024-01-01T00:00:00.000Z",
              "last_edited_time": "2024-01-02T00:00:00.000Z",
              "created_by": { "object": "user", "id": "u-1" },
              "last_edited_by": { "object": "user", "id": "u-1" },
              "has_children": true,
              "archived": false,
              "in_trash": false,
              "type": "child_page",
              "child_page": { "title": "01 A Family of Four Making Sixty-Six Thousand Can Still Qualify" }
            },
            {
              "object": "block",
              "id": "392a4ee6-9520-8188-8000-ca62aa7ed900",
              "parent": { "type": "page_id", "page_id": "392a4ee6-9520-81a3-a813-c8fb40860572" },
              "created_time": "2024-01-01T00:00:00.000Z",
              "last_edited_time": "2024-01-02T00:00:00.000Z",
              "created_by": { "object": "user", "id": "u-1" },
              "last_edited_by": { "object": "user", "id": "u-1" },
              "has_children": true,
              "archived": false,
              "in_trash": false,
              "type": "child_page",
              "child_page": { "title": "02 Getting Connected" }
            }
          ],
          "next_cursor": null,
          "has_more": false
        }
    """.trimIndent()

    @Test fun `official-API child_page blocks decode with type child_page and title`() {
        val list = apiJson.decodeFromString<NotionBlockList>(rawChildrenResponse)

        assertEquals("all 3 blocks decode", 3, list.results.size)
        val children = list.results.filter { it.type == "child_page" }
        assertEquals("both sub-pages recognized as child_page", 2, children.size)
        // The nested child_page payload must populate so planChapters can
        // read the title.
        assertEquals(
            "01 A Family of Four Making Sixty-Six Thousand Can Still Qualify",
            childPageTitle(children[0]),
        )
        assertEquals("02 Getting Connected", childPageTitle(children[1]))
    }

    @Test fun `decoded child_page blocks drive planChapters into intro plus one per short`() {
        val list = apiJson.decodeFromString<NotionBlockList>(rawChildrenResponse)

        val plans = planChapters(list.results)

        // Lead paragraph → Introduction; the two child_page sub-pages →
        // one Child chapter each. This is the split the shorts book should
        // show; a collapse-to-1 therefore cannot originate in this decode.
        assertEquals("intro + 2 shorts", 3, plans.size)
        assertTrue(plans[0] is NotionChapterPlan.Section)
        assertEquals("Introduction", plans[0].title)
        assertTrue(plans.drop(1).all { it is NotionChapterPlan.Child })
        assertEquals("02 Getting Connected", plans[2].title)
    }
}
