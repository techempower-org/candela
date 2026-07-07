package `in`.jphe.storyvox.llm.feature

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.ProviderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Voice Notes (#1657, Phase 3) — summarize a recording's transcript into a
 * title + key points + action items, streamed from the active LLM provider.
 *
 * An **interface** (impl [DefaultSummarizeTranscriptUseCase]) so consumers
 * (`NoteDetailViewModel`) depend on the abstraction and tests use a trivial
 * hand-rolled fake — no `LlmRepository`/provider construction in the feature
 * test (the same reason `PlaybackController` / `SettingsRepositoryUi` are seams).
 *
 * **Consent / privacy gate (spec §3.3):** summarizing is the ONLY path that
 * sends note text off-device. Implementations run only when BOTH hold: the
 * caller invokes it on an explicit user action (the "Summarize" tap *is* the
 * consent), AND an LLM provider is configured — otherwise they throw
 * [LlmError.NotConfigured] and the transcript is left untouched.
 */
interface SummarizeTranscriptUseCase {
    /**
     * Stream a summary of [transcript] (text deltas, in order). A blank
     * transcript yields an empty flow. The summary language follows
     * [transcriptLang] (EN/ES).
     *
     * @throws LlmError.NotConfigured when no LLM provider is configured — the
     *   consent/provider gate; the transcript is not sent off-device.
     */
    fun summarize(transcript: String, transcriptLang: String? = null): Flow<String>
}

/**
 * Default [SummarizeTranscriptUseCase] — a thin wrapper over
 * [LlmRepository.stream] that mirrors [ChapterRecap]. Does NOT gate on
 * [LlmConfig.sendChapterTextEnabled] (that toggle governs *chapter* text; a note
 * summary's consent is the explicit Summarize action itself).
 */
@Singleton
class DefaultSummarizeTranscriptUseCase @Inject constructor(
    private val llm: LlmRepository,
    private val configFlow: Flow<LlmConfig>,
) : SummarizeTranscriptUseCase {

    override fun summarize(transcript: String, transcriptLang: String?): Flow<String> = flow {
        val cfg = configFlow.first()
        if (cfg.provider == null) throw LlmError.NotConfigured(ProviderId.Claude)
        if (transcript.isBlank()) return@flow

        val userPrompt = buildString {
            append("Summarize the following transcript.\n\n")
            append(transcript.truncateForBudget(MAX_TRANSCRIPT_CHARS))
        }
        emitAll(
            llm.stream(
                messages = listOf(LlmMessage(LlmMessage.Role.user, userPrompt)),
                systemPrompt = systemPrompt(transcriptLang),
            ),
        )
    }

    private fun systemPrompt(lang: String?): String =
        BASE_SYSTEM_PROMPT.trimIndent().trim() + " " + languageInstruction(lang)

    companion object {
        /**
         * Transcript truncation budget (~3k input tokens). Claude has ~200k of
         * context; Ollama's default is ~8k, of which we leave room for the
         * summary + scaffolding. 12k chars ≈ 3k tokens fits both.
         */
        const val MAX_TRANSCRIPT_CHARS: Int = 12_000

        /** Summarizer persona — careful, won't invent, structured output. */
        internal const val BASE_SYSTEM_PROMPT: String = """
            You summarize a spoken-audio transcript (a voice memo or a meeting)
            for someone who was not there. Using Markdown, output: a one-line
            **Title**; then **Key points** as 3–7 concise bullets; then
            **Action items** as bullets (omit that section entirely if there are
            none). Base everything ONLY on the transcript — do not invent names,
            numbers, dates, or commitments. Be concise and neutral.
        """

        /** Instruct the model to write in the transcript's language. */
        internal fun languageInstruction(lang: String?): String =
            when (lang?.lowercase()?.take(2)) {
                null, "" -> "Write the summary in the same language as the transcript."
                "en" -> "Write the summary in English."
                "es" -> "Write the summary in Spanish."
                else ->
                    "Write the summary in the same language as the transcript " +
                        "(BCP-47/ISO language code: $lang)."
            }
    }
}

private fun String.truncateForBudget(maxChars: Int): String =
    if (length <= maxChars) this
    else take(maxChars) + "\n[…truncated for AI context budget…]"
