package `in`.jphe.storyvox.llm.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #216 / #1227 — contract tests for the [StoryvoxToolSpecs]
 * catalog. Locks in: exactly seven tools, non-blank descriptions, valid
 * JSON-schema shape for every parameter, the explicit shelf enum for
 * `add_to_shelf`, the speed clamp range for `set_speed`, and the
 * required/optional param split for `search_sources` / `get_book_details`.
 *
 * These are intentionally tight assertions — every advertised tool
 * round-trips through Anthropic's `input_schema` validator, so a
 * malformed schema breaks the chat request before the user notices.
 */
class StoryvoxToolSpecsTest {

    @Test
    fun `catalog has exactly the seven registered tools`() {
        val names = StoryvoxToolSpecs.ALL.map { it.name }.toSet()
        assertEquals(7, names.size)
        assertTrue(
            "Expected the full tool set",
            names == setOf(
                "add_to_shelf",
                "queue_chapter",
                "mark_chapter_read",
                "set_speed",
                "open_voice_library",
                "search_sources",
                "get_book_details",
            ),
        )
    }

    @Test
    fun `every spec has a non-blank description`() {
        StoryvoxToolSpecs.ALL.forEach { spec ->
            assertTrue(
                "Tool ${spec.name} should have a non-blank description",
                spec.description.isNotBlank(),
            )
            // Descriptions should actually guide the model — assert
            // they're meatier than a one-word stub. 80 chars is the
            // empirical minimum we've seen perform well on Haiku.
            assertTrue(
                "Tool ${spec.name} description should be >= 40 chars (was ${spec.description.length})",
                spec.description.length >= 40,
            )
        }
    }

    @Test
    fun `anthropic input_schema is a valid JSON schema for each tool`() {
        StoryvoxToolSpecs.ALL.forEach { spec ->
            val schema: JsonObject = spec.toAnthropicInputSchema()
            assertEquals(
                "Tool ${spec.name} schema should be type=object",
                "object",
                schema["type"]?.jsonPrimitive?.contentOrNull,
            )
            assertNotNull(
                "Tool ${spec.name} schema missing 'properties'",
                schema["properties"],
            )
            assertNotNull(
                "Tool ${spec.name} schema missing 'required'",
                schema["required"],
            )
            // Every named param shows up in properties; every required
            // param shows up in required.
            val props = schema["properties"]!!.jsonObject
            spec.parameters.forEach { p ->
                assertNotNull(
                    "Tool ${spec.name} missing parameter '${p.name}' in properties",
                    props[p.name],
                )
            }
            val required = schema["required"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
            val expectedRequired = spec.parameters
                .filter { it.required }
                .map { it.name }
                .toSet()
            assertEquals(
                "Tool ${spec.name} required-set mismatch",
                expectedRequired,
                required,
            )
        }
    }

    @Test
    fun `openai parameters mirrors anthropic input_schema`() {
        // OpenAI's `function.parameters` is the same JSON Schema as
        // Anthropic's `input_schema`. The two builders must agree.
        StoryvoxToolSpecs.ALL.forEach { spec ->
            assertEquals(
                "Tool ${spec.name} schemas should match between providers",
                spec.toAnthropicInputSchema(),
                spec.toOpenAiParameters(),
            )
        }
    }

    @Test
    fun `add_to_shelf restricts shelf to the three predefined values`() {
        val shelfParam = StoryvoxToolSpecs.addToShelf.parameters
            .single { it.name == "shelf" } as ToolParameter.StringParam
        assertEquals(
            "Reading / Read / Wishlist are the allowed shelves",
            listOf("Reading", "Read", "Wishlist"),
            shelfParam.allowedValues,
        )
        val schema = StoryvoxToolSpecs.addToShelf.toAnthropicInputSchema()
        val shelfSchema = schema["properties"]!!.jsonObject["shelf"]!!.jsonObject
        val enumValues = shelfSchema["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("Reading", "Read", "Wishlist"), enumValues)
    }

    @Test
    fun `set_speed param documents the visible slider range`() {
        val speedParam = StoryvoxToolSpecs.setSpeed.parameters
            .single { it.name == "speed" } as ToolParameter.FloatParam
        assertEquals(0.5f, speedParam.min!!, 0.001f)
        assertEquals(2.5f, speedParam.max!!, 0.001f)
        val schema = StoryvoxToolSpecs.setSpeed.toAnthropicInputSchema()
        val speedSchema = schema["properties"]!!.jsonObject["speed"]!!.jsonObject
        assertEquals(
            0.5,
            speedSchema["minimum"]!!.jsonPrimitive.content.toDouble(),
            0.001,
        )
        assertEquals(
            2.5,
            speedSchema["maximum"]!!.jsonPrimitive.content.toDouble(),
            0.001,
        )
    }

    @Test
    fun `open_voice_library takes no parameters`() {
        assertTrue(StoryvoxToolSpecs.openVoiceLibrary.parameters.isEmpty())
        val schema = StoryvoxToolSpecs.openVoiceLibrary.toAnthropicInputSchema()
        assertEquals(0, schema["properties"]!!.jsonObject.size)
        assertEquals(0, schema["required"]!!.jsonArray.size)
    }

    @Test
    fun `search_sources requires only query, with optional source and limit`() {
        val spec = StoryvoxToolSpecs.searchSources
        val required = spec.parameters.filter { it.required }.map { it.name }.toSet()
        assertEquals("Only query is required", setOf("query"), required)
        val optional = spec.parameters.filterNot { it.required }.map { it.name }.toSet()
        assertEquals(setOf("source", "limit"), optional)
        // The limit param advertises the catalog's clamp ceiling so the
        // model self-limits before the handler coerces.
        val limit = spec.parameters.single { it.name == "limit" } as ToolParameter.IntParam
        assertEquals(1, limit.min)
        assertEquals(StoryvoxToolSpecs.SEARCH_LIMIT_MAX, limit.max)
    }

    @Test
    fun `get_book_details requires only fictionId, with optional source`() {
        val spec = StoryvoxToolSpecs.getBookDetails
        val required = spec.parameters.filter { it.required }.map { it.name }.toSet()
        assertEquals(setOf("fictionId"), required)
        val optional = spec.parameters.filterNot { it.required }.map { it.name }.toSet()
        assertEquals(setOf("source"), optional)
    }

    @Test
    fun `search limit default is within the allowed range`() {
        assertTrue(
            "Default must sit inside 1..max",
            StoryvoxToolSpecs.SEARCH_LIMIT_DEFAULT in 1..StoryvoxToolSpecs.SEARCH_LIMIT_MAX,
        )
    }
}
