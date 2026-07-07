package `in`.jphe.storyvox.llm.feature

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.provider.AzureFoundryProvider
import `in`.jphe.storyvox.llm.provider.BedrockProvider
import `in`.jphe.storyvox.llm.provider.ClaudeApiProvider
import `in`.jphe.storyvox.llm.provider.FakeStore
import `in`.jphe.storyvox.llm.provider.OllamaProvider
import `in`.jphe.storyvox.llm.provider.OpenAiApiProvider
import `in`.jphe.storyvox.llm.provider.VertexProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SummarizeTranscriptUseCaseTest {

    private val fakeProvider = FakeProvider()

    private fun useCase(provider: ProviderId? = ProviderId.Claude): SummarizeTranscriptUseCase {
        val llm = LlmRepository(
            configFlow = flowOf(LlmConfig(provider = provider)),
            claude = fakeProvider.asClaude(),
            openAi = fakeProvider.asOpenAi(),
            ollama = fakeProvider.asOllama(),
            vertex = fakeProvider.asVertex(),
            foundry = fakeProvider.asFoundry(),
            bedrock = fakeProvider.asBedrock(),
            teams = fakeProvider.asTeams(),
        )
        return SummarizeTranscriptUseCase(llm = llm, configFlow = flowOf(LlmConfig(provider = provider)))
    }

    @Test
    fun `summarize streams provider tokens in order`() = runTest {
        fakeProvider.tokens = listOf("**Title**\n", "- point one\n", "- point two")
        val out = useCase().summarize("We shipped the thing and agreed to follow up Friday.").toList()
        assertEquals("**Title**\n- point one\n- point two", out.joinToString(""))
    }

    @Test
    fun `prompt asks for title, key points, action items and includes the transcript`() = runTest {
        fakeProvider.tokens = listOf("ok")
        val transcript = "Alice will send the report; Bob books the room."
        useCase().summarize(transcript).toList()

        val sys = fakeProvider.lastSystemPrompt.orEmpty()
        assertTrue("system prompt asks for a Title", sys.contains("Title"))
        assertTrue("system prompt asks for Key points", sys.contains("Key points"))
        assertTrue("system prompt asks for Action items", sys.contains("Action items"))
        assertTrue("system prompt forbids inventing", sys.contains("do not invent", ignoreCase = true))

        val userMsg = fakeProvider.lastMessages!!.first { it.role == LlmMessage.Role.user }.content
        assertTrue("user message carries the transcript", userMsg.contains(transcript))
    }

    @Test
    fun `summary language follows the transcript language`() = runTest {
        fakeProvider.tokens = listOf("ok")
        useCase().summarize("Hola, esto es una prueba.", transcriptLang = "es").toList()
        assertTrue(fakeProvider.lastSystemPrompt!!.contains("Spanish"))

        useCase().summarize("Hello, this is a test.", transcriptLang = "en").toList()
        assertTrue(fakeProvider.lastSystemPrompt!!.contains("English"))

        // Unknown / null → defer to the model, don't force a language.
        useCase().summarize("…", transcriptLang = null).toList()
        assertTrue(fakeProvider.lastSystemPrompt!!.contains("same language"))
    }

    @Test
    fun `throws NotConfigured and sends nothing when no provider is configured`() {
        fakeProvider.tokens = listOf("should not be sent")
        assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking { useCase(provider = null).summarize("secret transcript").toList() }
        }
        // Consent/provider gate: the transcript must never reach a provider.
        assertNull("no provider call on the gate path", fakeProvider.lastMessages)
    }

    @Test
    fun `blank transcript yields an empty flow and no provider call`() = runTest {
        fakeProvider.tokens = listOf("should not be sent")
        val out = useCase().summarize("   \n  ").toList()
        assertTrue("empty flow for blank transcript", out.isEmpty())
        assertNull("no provider call for blank transcript", fakeProvider.lastMessages)
    }

    @Test
    fun `long transcript is truncated for the context budget`() = runTest {
        fakeProvider.tokens = listOf("ok")
        val huge = "word ".repeat(6_000) // ~30k chars, over the 12k budget
        useCase().summarize(huge).toList()
        val userMsg = fakeProvider.lastMessages!!.first { it.role == LlmMessage.Role.user }.content
        assertTrue("truncation marker present", userMsg.contains("truncated for AI context budget"))
        assertFalse("full transcript not sent", userMsg.length > 20_000)
    }
}

/** Single-shape fake provider (mirrors ChapterRecapTest): one canned token list
 *  for every id slot, capturing the last messages + system prompt. */
private class FakeProvider {
    var tokens: List<String> = emptyList()
    var lastMessages: List<LlmMessage>? = null
    var lastSystemPrompt: String? = null

    private fun cannedStream(messages: List<LlmMessage>, systemPrompt: String?): Flow<String> {
        lastMessages = messages
        lastSystemPrompt = systemPrompt
        return flowOf(*tokens.toTypedArray())
    }

    fun asClaude(): ClaudeApiProvider = object : ClaudeApiProvider(
        http = OkHttpClient(), store = FakeStore(), configFlow = flowOf(LlmConfig()), json = Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            cannedStream(messages, systemPrompt)
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asOpenAi(): OpenAiApiProvider = object : OpenAiApiProvider(
        http = OkHttpClient(), store = FakeStore(), configFlow = flowOf(LlmConfig()), json = Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            cannedStream(messages, systemPrompt)
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asOllama(): OllamaProvider = object : OllamaProvider(
        http = OkHttpClient(), configFlow = flowOf(LlmConfig()), json = Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            cannedStream(messages, systemPrompt)
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asVertex(): VertexProvider = object : VertexProvider(
        http = OkHttpClient(), store = FakeStore(), configFlow = flowOf(LlmConfig()), json = Json,
        tokenSource = `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource(OkHttpClient()),
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            cannedStream(messages, systemPrompt)
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asFoundry(): AzureFoundryProvider = object : AzureFoundryProvider(
        http = OkHttpClient(), store = FakeStore(), configFlow = flowOf(LlmConfig()), json = Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            cannedStream(messages, systemPrompt)
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asBedrock(): BedrockProvider = object : BedrockProvider(
        http = OkHttpClient(), store = FakeStore(), configFlow = flowOf(LlmConfig()), json = Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            cannedStream(messages, systemPrompt)
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asTeams(): `in`.jphe.storyvox.llm.provider.AnthropicTeamsProvider =
        object : `in`.jphe.storyvox.llm.provider.AnthropicTeamsProvider(
            http = OkHttpClient(), store = FakeStore(), configFlow = flowOf(LlmConfig()),
            authApi = `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthApi(OkHttpClient()),
            authRepo = `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository(FakeStore()),
            json = Json,
        ) {
            override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
                cannedStream(messages, systemPrompt)
            override suspend fun probe(): ProbeResult = ProbeResult.Ok
        }
}
