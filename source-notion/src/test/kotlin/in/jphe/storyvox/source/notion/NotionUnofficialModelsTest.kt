package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.source.notion.net.NotionChunkResponse
import `in`.jphe.storyvox.source.notion.net.NotionQueryCollectionResponse
import `in`.jphe.storyvox.source.notion.net.NotionRecordMap
import `in`.jphe.storyvox.source.notion.net.NotionUnofficialError
import `in`.jphe.storyvox.source.notion.net.contentIds
import `in`.jphe.storyvox.source.notion.net.rowsReturned
import `in`.jphe.storyvox.source.notion.net.rowsTotal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #393 — round-trip tests for the unofficial-API wire shapes.
 * Uses verbatim fragments from the live TechEmpower responses
 * (captured 2026-05-13) so we'd catch a schema-shift regression
 * against the actual Notion edge response.
 */
class NotionUnofficialModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `loadPageChunk response decodes a TechEmpower-shaped recordMap`() {
        // Trimmed but structurally faithful — root page + one child block.
        val raw = """
        {
          "cursor":{"stack":[]},
          "recordMap":{
            "__version__":3,
            "block":{
              "0959e445-9998-4143-acab-c80187305001":{
                "spaceId":"70f6edce-ef9d-4a38-9d00-7618cde046d0",
                "value":{"value":{
                  "id":"0959e445-9998-4143-acab-c80187305001",
                  "type":"page",
                  "properties":{"title":[["Welcome to TechEmpower.org"]]},
                  "content":["a7192a1d-3fd9-4d50-b5da-ac1f94480f7e","2a3d7068-03c6-4940-9e74-e9ce5ccd4c4b"]
                },"role":"reader"}
              },
              "2a3d7068-03c6-4940-9e74-e9ce5ccd4c4b":{
                "spaceId":"70f6edce-ef9d-4a38-9d00-7618cde046d0",
                "value":{"value":{
                  "id":"2a3d7068-03c6-4940-9e74-e9ce5ccd4c4b",
                  "type":"collection_view",
                  "collection_id":"8cb5379d-fe78-4a15-9f3a-d539f5a60387",
                  "view_ids":["329a4ee6-9520-8199-bc52-000caf9e1475"]
                },"role":"reader"}
              }
            }
          }
        }
        """.trimIndent()
        val parsed = json.decodeFromString<NotionChunkResponse>(raw)
        val rm = parsed.recordMap
        val root = rm.findBlock("0959e44599984143acabc80187305001")
        assertNotNull(root)
        assertEquals("Welcome to TechEmpower.org", readTitle(root!!))
        assertEquals(
            listOf("a7192a1d-3fd9-4d50-b5da-ac1f94480f7e", "2a3d7068-03c6-4940-9e74-e9ce5ccd4c4b"),
            root.contentIds(),
        )
        val coll = rm.findBlock("2a3d706803c649409e74e9ce5ccd4c4b")
        assertNotNull(coll)
        assertEquals("collection_view", coll!!.blockType())
        assertEquals("8cb5379d-fe78-4a15-9f3a-d539f5a60387", coll.collectionId())
        assertEquals("329a4ee6-9520-8199-bc52-000caf9e1475", coll.firstViewId())
    }

    @Test
    fun `queryCollection response decodes recordMap and result envelope`() {
        // Fragment from the live Resources DB query — one row + the
        // reducerResults shape.
        val raw = """
        {
          "result":{
            "type":"reducer",
            "reducerResults":{
              "collection_group_results":{
                "type":"results",
                "blockIds":["309a4ee6-9520-817a-aa23-ebc00eebbe32"]
              }
            },
            "sizeHint":1
          },
          "recordMap":{
            "__version__":3,
            "block":{
              "309a4ee6-9520-817a-aa23-ebc00eebbe32":{
                "spaceId":"70f6edce-ef9d-4a38-9d00-7618cde046d0",
                "value":{"value":{
                  "id":"309a4ee6-9520-817a-aa23-ebc00eebbe32",
                  "type":"page",
                  "properties":{"title":[["GitHub Student Developer Pack"]]}
                },"role":"reader"}
              }
            },
            "collection":{}
          }
        }
        """.trimIndent()
        val parsed = json.decodeFromString<NotionQueryCollectionResponse>(raw)
        assertEquals(1, parsed.recordMap.block.size)
        val first = parsed.recordMap.findBlock("309a4ee6-9520-817a-aa23-ebc00eebbe32")
        assertNotNull(first)
        assertEquals("GitHub Student Developer Pack", readTitle(first!!))
    }

    @Test
    fun `rowsTotal and rowsReturned expose pagination signal from reducer envelope`() {
        // Issue #698 — server reports `total` separately from the
        // capped `blockIds` array. The pagination loop in
        // NotionUnofficialApi.queryCollection keys off both.
        val raw = """
        {
          "result":{
            "type":"reducer",
            "reducerResults":{
              "collection_group_results":{
                "type":"results",
                "total":237,
                "blockIds":["id-a","id-b","id-c"]
              }
            },
            "sizeHint":3
          },
          "recordMap":{"__version__":3,"block":{},"collection":{}}
        }
        """.trimIndent()
        val parsed = json.decodeFromString<NotionQueryCollectionResponse>(raw)
        assertEquals(237, parsed.rowsTotal())
        assertEquals(3, parsed.rowsReturned())
    }

    @Test
    fun `rowsTotal returns null when reducer envelope is missing or malformed`() {
        // Older / unexpected response shapes — must not throw.
        val noResult = json.decodeFromString<NotionQueryCollectionResponse>(
            """{"recordMap":{"__version__":3,"block":{},"collection":{}}}""",
        )
        assertNull(noResult.rowsTotal())
        assertEquals(0, noResult.rowsReturned())

        val resultNoTotal = json.decodeFromString<NotionQueryCollectionResponse>(
            """
            {"result":{"reducerResults":{"collection_group_results":{"blockIds":[]}}},
             "recordMap":{"__version__":3,"block":{},"collection":{}}}
            """.trimIndent(),
        )
        assertNull(resultNoTotal.rowsTotal())
        assertEquals(0, resultNoTotal.rowsReturned())
    }

    @Test
    fun `NotionUnofficialError decodes Notion error envelope`() {
        val raw = """
        {"isNotionError":true,"errorId":"abc","name":"MemcachedCrossCellError","debugMessage":"Cross-cell memcached access is not allowed","message":"Something went wrong. (502)"}
        """.trimIndent()
        val err = json.decodeFromString<NotionUnofficialError>(raw)
        assertTrue(err.isNotionError)
        assertEquals("MemcachedCrossCellError", err.name)
        assertEquals("Something went wrong. (502)", err.message)
    }

    @Test
    fun `recordMap tolerates unknown top-level fields`() {
        // Notion adds fields to recordMap over time (automation,
        // collection_view, ...). Decoding must not throw on unknowns.
        val raw = """
        {"recordMap":{"__version__":3,"block":{},"automation":{"foo":{}},"new_field_2027":{}}}
        """.trimIndent()
        val parsed = json.decodeFromString<NotionChunkResponse>(raw)
        assertEquals(0, parsed.recordMap.block.size)
    }
}
