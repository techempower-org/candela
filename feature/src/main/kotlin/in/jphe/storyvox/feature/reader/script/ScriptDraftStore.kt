package `in`.jphe.storyvox.feature.reader.script

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #1366 — the hand-off seam between the AI script writer and the
 * reader's teleprompter.
 *
 * The generator is a screen-scoped ViewModel; the teleprompter consumer is
 * the reader composition (and, later, #1368's voice-paced teleprompter).
 * They live in different scopes and can't share Compose state directly, so
 * the finished script is parked here in a `@Singleton` — exactly the shape
 * [`in`.jphe.storyvox.playback.TeleprompterController] uses to hoist the
 * transport controls so both the phone reader and the Wear bridge can reach
 * them.
 *
 * ## Scope (v1 — the producer half)
 * [ScriptGeneratorViewModel.loadIntoTeleprompter] writes [activeScript] and
 * flips the teleprompter transport on. Rendering the parked script *as* the
 * teleprompter's scroll content is the integration follow-up: it touches the
 * reader's shared text / TTS / highlight path (also owned by #1368), so it's
 * deliberately kept out of this one-button change. Consumers observe
 * [activeScript] and call [clear] once they've taken ownership.
 */
@Singleton
class ScriptDraftStore @Inject constructor() {

    private val _activeScript = MutableStateFlow<String?>(null)

    /** The script most recently loaded for teleprompter rehearsal, or null
     *  when none is pending. */
    val activeScript: StateFlow<String?> = _activeScript.asStateFlow()

    /** Park [script] for the reader to pick up. */
    fun load(script: String) {
        _activeScript.value = script
    }

    /** Drop the pending script once a consumer has taken it (or on reset). */
    fun clear() {
        _activeScript.value = null
    }
}
