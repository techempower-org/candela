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
 * Issue #216 — wire-shape test for [OpenAiApiProvider.chatWithTools].
 * The OpenAI format wraps each tool in
 * `{type: "function", function: {name, description, parameters}}`,
 * not the flatter Anthropic shape; this test pins both the wrapper
 * and the parameters' JSON-Schema shape.
 */
class OpenAiApiProviderToolsTest {

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

    private fun providerWith(key: String?): OpenAiApiProvider {
        val testEndpoint = server.url("/v1/chat/completions").toString()
        return object : OpenAiApiProvider(
            http = http,
            store = FakeStore(openAi = key),
            configFlow = flowOf(LlmConfig(openAiModel = "gpt-4o-mini")),
            json = json,
        ) {
            override val endpointUrl: String = testEndpoint
        }
    }

    @Test
    fun `chatWithTools serialises catalog into OpenAI tool function shape`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id":"chatcmpl-1",
                      "choices":[{
                        "index":0,
                        "message":{"role":"assistant","content":"hi"},
                        "finish_reason":"stop"
                      }]
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
        assertNotNull(toolsArr)
        // Every catalog tool is serialised exactly once, by name. Compared by
        // name rather than count, so an empty or duplicated catalog can't pass.
        val sentNames = toolsArr!!.map { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
        assertEquals(StoryvoxToolSpecs.ALL.map { it.name }, sentNames)
        assertEquals("tool names must be unique", sentNames.size, sentNames.toSet().size)
        assertTrue("catalog must not be empty", sentNames.isNotEmpty())
        val firstTool = toolsArr[0].jsonObject
        assertEquals(
            "function",
            firstTool["type"]?.jsonPrimitive?.contentOrNull,
        )
        val function = firstTool["function"]!!.jsonObject
        assertEquals(
            "add_to_shelf",
            function["name"]?.jsonPrimitive?.contentOrNull,
        )
        assertNotNull(function["description"])
        val params = function["parameters"]!!.jsonObject
        assertEquals("object", params["type"]?.jsonPrimitive?.contentOrNull)
        // Non-streaming when tools are present.
        val streamField = body["stream"]?.jsonPrimitive?.content?.toBoolean()
        assertTrue(
            "Expected stream=false or absent, was $streamField",
            streamField != true,
        )
    }

    @Test
    fun `chatWithTools runs handler on tool_calls response and threads result back`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id":"chatcmpl-1",
                      "choices":[{
                        "index":0,
                        "message":{
                          "role":"assistant",
                          "content":null,
                          "tool_calls":[{
                            "id":"call_xyz",
                            "type":"function",
                            "function":{
                              "name":"set_speed",
                              "arguments":"{\"speed\":1.5}"
                            }
                          }]
                        },
                        "finish_reason":"tool_calls"
                      }]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id":"chatcmpl-2",
                      "choices":[{
                        "index":0,
                        "message":{"role":"assistant","content":"Sped up."},
                        "finish_reason":"stop"
                      }]
                    }
                    """.trimIndent(),
                ),
        )

        val handlerCalls = mutableListOf<String>()
        val tools = ToolRegistry.build(
            specs = StoryvoxToolSpecs.ALL,
            lookup = { name ->
                ToolHandler { _ ->
                    handlerCalls += name
                    ToolResult.Success("Set to 1.5x.")
                }
            },
        )
        val events = providerWith("k").chatWithTools(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "faster")),
            tools = tools,
        ).toList()

        assertEquals(listOf("set_speed"), handlerCalls)
        assertTrue(events.first() is ChatStreamEvent.ToolCallStarted)
        val completed = events[1] as ChatStreamEvent.ToolCallCompleted
        assertEquals("call_xyz", completed.id)
        assertEquals(
            "Sped up.",
            events.filterIsInstance<ChatStreamEvent.TextDelta>()
                .joinToString("") { it.text },
        )
        assertEquals(2, server.requestCount)
        val secondReq = run {
            server.takeRequest()
            server.takeRequest()
        }
        val secondBody = json.parseToJsonElement(secondReq.body.readUtf8()).jsonObject
        val messages = secondBody["messages"]!!.jsonArray
        // user + assistant(tool_calls) + tool = 3
        assertEquals(3, messages.size)
        val toolMsg = messages[2].jsonObject
        assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "call_xyz",
            toolMsg["tool_call_id"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "Set to 1.5x.",
            toolMsg["content"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
