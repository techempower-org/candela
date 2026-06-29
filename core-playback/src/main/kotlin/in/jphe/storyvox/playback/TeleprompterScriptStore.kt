package `in`.jphe.storyvox.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #1369 — the one-way seam that lets a caller push arbitrary text into
 * the teleprompter **without a backing `Fiction`/`Chapter`**.
 *
 * The teleprompter (#1239) historically scrolled only the *current chapter's*
 * body, reachable solely through the reader's normal chapter pipeline. Two
 * features need to feed it free text instead:
 *   - #1369 Script Manager — "Load this saved script into the teleprompter".
 *   - #1366 AI Script Writer — "Run this just-generated script in the teleprompter".
 *
 * Rather than each feature inventing its own reader hook (and clobbering
 * `ReaderView` in parallel), both write a [PendingTeleprompterScript] here and
 * navigate to the player. The reader observes [pending] and, when non-null,
 * renders that text in teleprompter mode instead of the chapter body, then
 * calls [consume] so a back-out + return doesn't re-trigger it.
 *
 * Lives in `:core-playback` (not `:feature`) for the same reason as
 * [TeleprompterController]: it's a `@Singleton` both the UI and non-Compose
 * layers can reach, and it has no Compose dependency.
 */
@Singleton
class TeleprompterScriptStore @Inject constructor() {

    private val _pending = MutableStateFlow<PendingTeleprompterScript?>(null)

    /**
     * The script queued for the teleprompter, or null when none is pending
     * (the reader falls back to the normal chapter body). Set by [load],
     * cleared by [consume]/[clear].
     */
    val pending: StateFlow<PendingTeleprompterScript?> = _pending.asStateFlow()

    /** Queue [script] for the teleprompter. Replaces any prior pending script. */
    fun load(script: PendingTeleprompterScript) { _pending.value = script }

    /**
     * Atomically read-and-clear the pending script. The reader calls this once
     * it has taken ownership of the text so navigating away and back doesn't
     * reload the same script over the user's place. Returns null if nothing
     * was pending.
     */
    fun consume(): PendingTeleprompterScript? {
        val current = _pending.value
        _pending.value = null
        return current
    }

    /** Drop any pending script without consuming it (e.g. user cancelled). */
    fun clear() { _pending.value = null }
}

/**
 * A piece of text destined for the teleprompter.
 *
 * @property id The source [`in`.jphe.storyvox.data] script id when loaded from
 *   a saved row (#1369), or null for ephemeral/unsaved text (e.g. an AI draft,
 *   #1366). Carried only so the reader/consumer can attribute or persist
 *   position later; the teleprompter itself only needs [title] + [body].
 * @property title Display title for the teleprompter chrome (may be blank).
 * @property body The text to scroll.
 */
data class PendingTeleprompterScript(
    val id: String? = null,
    val title: String,
    val body: String,
)
