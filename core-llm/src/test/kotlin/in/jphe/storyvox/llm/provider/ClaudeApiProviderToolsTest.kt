package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.tools.ChatStreamEvent
import `in`.jphe.storyvox.llm.tools.StoryvoxToolSpecs
import `in`.jphe.storyvox.llm.tools.ToolHandler
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import `in`.jphe.storyvox.llm.tools.ToolResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Issue #216 — wire-shape tests for [ClaudeApiProvider.chatWithTools].
 *
 * Two scenarios:
 *  - Tool catalog is serialized into the outgoing request body in
 *    Anthropic shape: `tools: [{name, description, input_schema}]`.
 *  - A simulated tool_use response triggers the matching handler and
 *    emits [ChatStreamEvent.ToolCallStarted] / [ToolCallCompleted] in
 *    order, then the follow-up text.
 */
class ClaudeApiProviderToolsTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun providerWith(key: String?): ClaudeApiProvider {
        val testEndpoint = server.url("/v1/messages").toString()
        return object : ClaudeApiProvider(
            http = http,
            store = FakeStore(claude = key),
            configFlow = flowOf(LlmConfig(claudeModel = "claude-haiku-4.5")),
            json = json,
        ) {
            override val endpointUrl: String = testEndpoint
        }
    }

    @Test
    fun `chatWithTools serialises catalog into request body`() = runTest {
        // First response: plain text (no tool_use) — keeps the test
        // focused on the request shape; tool-call execution has its
        // own test below.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id":"msg_1",
                      "type":"message",
                      "role":"assistant",
                      "content":[{"type":"text","text":"hi"}],
                      "stop_reason":"end_turn"
                    }
                    """.trimIndent(),
                ),
        )
        val tools = ToolRegistry.build(
            specs = StoryvoxToolSpecs.ALL,
            lookup = { ToolHandler { ToolResult.Success("noop") } },
        )
        providerWith("k").chatWithTools(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "ping")),
            tools = tools,
        ).toList()

        val req = server.takeRequest()
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val toolsArr = body["tools"]?.jsonArray
        assertNotNull("Request must include `tools` array", toolsArr)
        // Every spec in the catalog is serialised (5 → 7 when search_sources +
        // get_book_details landed; pinning a literal drifted silently).
        assertEquals(StoryvoxToolSpecs.ALL.size, toolsArr!!.size)
        val firstTool = toolsArr[0].jsonObject
        assertEquals(
            "add_to_shelf",
            firstTool["name"]?.jsonPrimitive?.contentOrNull,
        )
        assertNotNull(
            "tool must include description",
            firstTool["description"]?.jsonPrimitive?.contentOrNull,
        )
        val inputSchema = firstTool["input_schema"]?.jsonObject
        assertNotNull("tool must include input_schema", inputSchema)
        assertEquals(
            "object",
            inputSchema!!["type"]?.jsonPrimitive?.contentOrNull,
        )
        // Non-streaming when tools are present — the stream field
        // is either absent (kotlinx.serialization skips defaults) or
        // explicitly false.
        val streamField = body["stream"]?.jsonPrimitive?.content?.toBoolean()
        assertTrue(
            "Expected stream=false or absent, was $streamField",
            streamField != true,
        )
    }

    @Test
    fun `chatWithTools runs handler then threads result back to model`() = runTest {
        // First round: model asks to call add_to_shelf.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id":"msg_1",
                      "type":"message",
                      "role":"assistant",
                      "content":[{
                        "type":"tool_use",
                        "id":"toolu_abc",
                        "name":"add_to_shelf",
                        "input":{"fictionId":"f1","shelf":"Reading"}
                      }],
                      "stop_reason":"tool_use"
                    }
                    """.trimIndent(),
                ),
        )
        // Second round: model has the result, responds with text.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id":"msg_2",
                      "type":"message",
                      "role":"assistant",
                      "content":[{"type":"text","text":"Done."}],
                      "stop_reason":"end_turn"
                    }
                    """.trimIndent(),
                ),
        )

        val handlerCalls = mutableListOf<String>()
        val tools = ToolRegistry.build(
            specs = StoryvoxToolSpecs.ALL,
            lookup = { name ->
                ToolHandler { args ->
                    handlerCalls += name
                    ToolResult.Success("Added to Reading.")
                }
            },
        )
        val events = providerWith("k").chatWithTools(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "shelf it")),
            tools = tools,
        ).toList()

        // Handler ran exactly once with the matching name.
        assertEquals(listOf("add_to_shelf"), handlerCalls)

        // Event order: ToolCallStarted → ToolCallCompleted → TextDelta.
        assertTrue(
            "First event should be ToolCallStarted, was ${events.firstOrNull()}",
            events.first() is ChatStreamEvent.ToolCallStarted,
        )
        val started = events.first() as ChatStreamEvent.ToolCallStarted
        assertEquals("add_to_shelf", started.call.name)
        assertEquals("toolu_abc", started.call.id)
        val completed = events[1] as ChatStreamEvent.ToolCallCompleted
        assertEquals("toolu_abc", completed.id)
        assertTrue(completed.result is ToolResult.Success)
        val text = events.filterIsInstance<ChatStreamEvent.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("Done.", text)

        // Two requests should have hit the server — the tool round
        // and the follow-up round with the tool result threaded in.
        assertEquals(2, server.requestCount)
        val secondReq = run {
            server.takeRequest() // discard the first
            server.takeRequest()
        }
        val secondBody = json.parseToJsonElement(secondReq.body.readUtf8()).jsonObject
        val messages = secondBody["messages"]!!.jsonArray
        // user(ping) + assistant(tool_use) + user(tool_result) = 3
        assertEquals(3, messages.size)
        val lastMsg = messages[2].jsonObject
        assertEquals("user", lastMsg["role"]?.jsonPrimitive?.contentOrNull)
        val resultBlock = lastMsg["content"]!!.jsonArray[0].jsonObject
        assertEquals(
            "tool_result",
            resultBlock["type"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "toolu_abc",
            resultBlock["tool_use_id"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
