package `in`.jphe.storyvox.feature.reader.script

import androidx.compose.runtime.Immutable

/**
 * Issue #1366 — UI state for the AI script writer sheet.
 *
 * One flow, four resting states: the topic awaits input ([Idle]), a
 * generation streams in ([Generating] carries the text so far so the sheet
 * can render tokens as they arrive), a finished or user-edited script is
 * ready to rehearse ([Done]), or something went wrong ([Error]).
 *
 * [Done] also covers a *stopped* generation (the user tapped Stop with
 * partial text) and an *edited* script — both are "a script the user can
 * load", so they share the one terminal state rather than multiplying
 * cases the UI would treat identically.
 */
@Immutable
sealed interface ScriptGeneratorState {

    /** Nothing generated yet — the topic field + length picker await input. */
    data object Idle : ScriptGeneratorState

    /** A generation is in flight; [partial] is the text streamed so far. */
    @Immutable
    data class Generating(val partial: String) : ScriptGeneratorState

    /** A finished (or stopped, or hand-edited) script ready to load. */
    @Immutable
    data class Done(val script: String) : ScriptGeneratorState

    /**
     * Generation failed. [routeToSettings] is true when the cause was an
     * unconfigured / disabled / mis-keyed provider, so the sheet offers an
     * "Open AI settings" CTA instead of a bare retry — mirroring the recap
     * surface's NotConfigured handling (#152).
     */
    @Immutable
    data class Error(
        val message: String,
        val routeToSettings: Boolean = false,
    ) : ScriptGeneratorState
}
