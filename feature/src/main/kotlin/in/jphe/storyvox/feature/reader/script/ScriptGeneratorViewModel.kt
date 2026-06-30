package `in`.jphe.storyvox.feature.reader.script

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.playback.PendingTeleprompterScript
import `in`.jphe.storyvox.playback.TeleprompterController
import `in`.jphe.storyvox.playback.TeleprompterScriptStore
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Issue #1366 — AI script writer for the teleprompter.
 *
 * Streams a short-form video script for a topic + target duration through
 * the active LLM provider, following the same `LlmRepository.stream()` shape
 * as [`in`.jphe.storyvox.llm.feature.ChapterRecap]: build a system prompt +
 * user prompt, collect the `Flow<String>` of deltas, accumulate. The pace
 * knobs (system prompt, word-count target, duration estimate) are pulled out
 * into pure top-level helpers below so they're unit-testable without Hilt,
 * coroutines, or a live provider.
 *
 * The finished script is handed to the teleprompter via the shared
 * [TeleprompterScriptStore] (the cross-scope seam) plus
 * [TeleprompterController.setEnabled] — see [loadIntoTeleprompter].
 */
@HiltViewModel
class ScriptGeneratorViewModel @Inject constructor(
    private val llm: LlmRepository,
    private val configFlow: Flow<LlmConfig>,
    private val teleprompter: TeleprompterController,
    private val store: TeleprompterScriptStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ScriptGeneratorState>(ScriptGeneratorState.Idle)
    val state: StateFlow<ScriptGeneratorState> = _state.asStateFlow()

    /** The in-flight generation, cancelled cooperatively on Regenerate /
     *  Stop so a replaced stream can't keep writing state. */
    private var genJob: Job? = null

    /**
     * Stream a script for [topic] at the given [durationSecs] target. A new
     * call cancels any in-flight generation. Blank topics are ignored (the
     * sheet also disables the button). Emits [ScriptGeneratorState.Generating]
     * as tokens arrive, then [ScriptGeneratorState.Done], or
     * [ScriptGeneratorState.Error] on failure.
     */
    fun generateScript(topic: String, durationSecs: Int = DEFAULT_DURATION_SECS) {
        val trimmed = topic.trim()
        if (trimmed.isEmpty()) return

        genJob?.cancel()
        val buf = StringBuilder()
        _state.value = ScriptGeneratorState.Generating("")
        genJob = viewModelScope.launch {
            // Up-front config check gives a clean "route to Settings" path
            // without flashing a doomed Generating state through the wire
            // (mirrors ChapterRecap's pre-stream guard). `llm.stream` would
            // also throw NotConfigured — handled in [mapError] as a backstop.
            val cfg = configFlow.first()
            if (cfg.provider == null) {
                _state.value = notConfiguredError()
                return@launch
            }
            llm.stream(
                messages = listOf(
                    LlmMessage(LlmMessage.Role.user, buildScriptUserPrompt(trimmed, durationSecs)),
                ),
                systemPrompt = buildScriptSystemPrompt(durationSecs),
            )
                .catch { e -> _state.value = mapError(e) }
                .onCompletion { cause ->
                    // Only a clean completion finalises, and only if we're
                    // still Generating — a `.catch` that set Error, or a
                    // Stop() that already wrote Done, must not be clobbered
                    // by the normal-completion path (catch swallows the
                    // throwable, so the flow then completes with cause=null).
                    if (cause == null && _state.value is ScriptGeneratorState.Generating) {
                        _state.value = ScriptGeneratorState.Done(buf.toString().trim())
                    }
                }
                .collect { delta ->
                    buf.append(delta)
                    _state.value = ScriptGeneratorState.Generating(buf.toString())
                }
        }
    }

    /**
     * Stop an in-flight generation, keeping whatever streamed so far as a
     * [ScriptGeneratorState.Done] the user can still load or edit. Falls back
     * to [ScriptGeneratorState.Idle] when nothing usable arrived yet.
     */
    fun stop() {
        val current = _state.value
        genJob?.cancel()
        genJob = null
        if (current is ScriptGeneratorState.Generating) {
            val partial = current.partial.trim()
            _state.value =
                if (partial.isEmpty()) ScriptGeneratorState.Idle
                else ScriptGeneratorState.Done(partial)
        }
    }

    /** Replace the working script with a user-edited version. */
    fun setScript(text: String) {
        _state.value = ScriptGeneratorState.Done(text)
    }

    /**
     * Hand the finished script to the teleprompter: park it in the shared
     * [TeleprompterScriptStore] for the reader to pick up and flip the transport
     * on. No-op unless a non-blank [ScriptGeneratorState.Done] script is ready.
     *
     * Issue #1369 follow-up — unified onto [TeleprompterScriptStore]
     * (core-playback) so the AI writer (#1366) and the script manager (#1369)
     * feed the same seam, and the reader consumes one store. AI drafts carry no
     * title yet (the editor/manager is where a script gets named).
     */
    fun loadIntoTeleprompter() {
        val script = (_state.value as? ScriptGeneratorState.Done)?.script?.trim().orEmpty()
        if (script.isEmpty()) return
        store.load(PendingTeleprompterScript(title = "", body = script))
        teleprompter.setEnabled(true)
    }

    /** Estimated spoken duration of [text] in whole seconds at [wpm]. */
    fun estimatedDuration(text: String, wpm: Int = SCRIPT_WPM): Int =
        estimatedDurationSecs(text, wpm)

    /** Whitespace-delimited word count of [text]. */
    fun wordCount(text: String): Int = scriptWordCount(text)

    private fun mapError(e: Throwable): ScriptGeneratorState.Error = when (e) {
        is LlmError.NotConfigured -> notConfiguredError()
        is LlmError.AuthFailed -> ScriptGeneratorState.Error(
            "${e.provider} key is invalid — check Settings → AI.",
            routeToSettings = true,
        )
        is LlmError.Transport -> ScriptGeneratorState.Error(
            "Couldn't reach the AI — check your connection and try again.",
        )
        is LlmError.ProviderError -> ScriptGeneratorState.Error(
            "AI service error (${e.status}). Try again in a moment.",
        )
        else -> ScriptGeneratorState.Error(e.message ?: "Generation failed.")
    }

    private fun notConfiguredError() = ScriptGeneratorState.Error(
        "AI isn't set up yet. Choose a provider in Settings → AI.",
        routeToSettings = true,
    )

    companion object {
        const val DEFAULT_DURATION_SECS: Int = 60
    }
}

// ── Pure helpers (unit-tested in ScriptGeneratorLogicTest) ──────────────────

/**
 * Standard short-form speaking pace (words/min). Used for both the generation
 * word-count target and the duration estimate so the sheet's "~Ns" readout
 * matches what we asked the model to write. The teleprompter's *scroll* pace
 * is a separate, user-adjustable knob ([TeleprompterController.wpm]).
 */
internal const val SCRIPT_WPM: Int = 150

/** Duration presets offered as chips in the sheet (seconds). */
internal val SCRIPT_DURATION_OPTIONS: List<Int> = listOf(30, 60, 90)

private val WHITESPACE = Regex("\\s+")

// The teleprompter format (matching the TechEmpower Show scripts) carries
// non-spoken scaffolding: `[cue]` blocks, `=====` section-banner rules, and
// leading `SPEAKER:` labels. None of it is read aloud, so it must not inflate
// the spoken-duration estimate. Banner *title* lines (ALL-CAPS, no `=`) are a
// known, accepted exception — detecting them needs block parsing we don't do.
private val CUE_SPAN = Regex("\\[[^\\]]*\\]")
private val BANNER_RULE = Regex("(?m)^\\s*=+\\s*$")
private val SPEAKER_LABEL = Regex("(?m)^\\s*[A-Z][A-Z0-9 .'’&/-]{0,30}:")

/** Target word count for [durationSecs] of speech at [wpm]. */
internal fun targetWordCount(durationSecs: Int, wpm: Int = SCRIPT_WPM): Int =
    if (durationSecs <= 0 || wpm <= 0) 0 else (durationSecs * wpm) / 60

/** Strip non-spoken scaffolding — `[cues]`, `=====` banner rules, and leading
 *  `SPEAKER:` labels — leaving just what a presenter reads aloud. */
internal fun spokenText(raw: String): String =
    raw
        .replace(CUE_SPAN, " ")
        .replace(BANNER_RULE, " ")
        .replace(SPEAKER_LABEL, " ")

/** Spoken-word count (scaffolding stripped); blank text is zero words. */
internal fun scriptWordCount(text: String): Int {
    val spoken = spokenText(text).trim()
    return if (spoken.isEmpty()) 0 else spoken.split(WHITESPACE).size
}

/** Estimated spoken duration of [text] in whole seconds at [wpm]. */
internal fun estimatedDurationSecs(text: String, wpm: Int = SCRIPT_WPM): Int =
    if (wpm <= 0) 0 else Math.round(scriptWordCount(text) * 60.0 / wpm).toInt()

/**
 * The teleprompter-script system prompt (#1366). Short and explicit, like
 * ChapterRecap's librarian persona — interpolates the duration + word-count
 * target so the model paces to length.
 *
 * The output format mirrors the TechEmpower Show teleprompter scripts so a
 * generated script renders cleanly in the same prompter: blocks split by blank
 * lines, `SPEAKER:` labels, `[bracketed]` production cues, and optional `===`
 * section banners. (Reference: techempower/show/ep2/teleprompter.html.)
 */
internal fun buildScriptSystemPrompt(durationSecs: Int, wpm: Int = SCRIPT_WPM): String {
    val words = targetWordCount(durationSecs, wpm)
    return """
        You are a script writer for short-form video (YouTube Shorts, TikTok, Reels),
        writing for a teleprompter. Write a script for the given topic and duration.

        Format it so it renders cleanly in the teleprompter:
        - Separate every block with a blank line.
        - Begin each spoken block with a SPEAKER label in capitals on its own line,
          ending in a colon (for example "HOST:"). Use one speaker for a solo piece;
          add a second named speaker only when the topic is a conversation.
        - Put production cues — cuts, b-roll, on-screen text, post notes — in [SQUARE
          BRACKETS], on their own line or inline. Cues are never spoken aloud.
        - For a longer piece you may add a section banner as its own block: a line of
          ===, a short ALL-CAPS title, then another line of ===.

        Write to be spoken:
        - Conversational, direct tone — as if speaking to the camera.
        - Open with a hook (a question, bold claim, or surprising fact) in the first
          five words.
        - Keep sentences short — 8 to 12 words.
        - Spell out numbers, prices, and web addresses as spoken words, not digits.
        - Pace for about ${durationSecs}s at $wpm words per minute (~$words spoken words). Labels and cues do not count toward that.
        - End with a clear call-to-action or a memorable closing line.
        - Output only the script — no preamble, no explanation, no markdown.
    """.trimIndent()
}

/** The per-request user prompt carrying the topic + length target. */
internal fun buildScriptUserPrompt(topic: String, durationSecs: Int, wpm: Int = SCRIPT_WPM): String {
    val words = targetWordCount(durationSecs, wpm)
    return "Topic: ${topic.trim()}\n" +
        "Target: about $durationSecs seconds (~$words spoken words).\n" +
        "Write the script now, in the teleprompter format described."
}
