package `in`.jphe.storyvox.playback

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Issue #1776 — result of a one-shot read-aloud request
 * ([PlaybackController.speakTextAwaitingEngine]).
 *
 * Before #1776 `speakText` was a silent no-op on a cold process: no
 * [StoryvoxPlaybackService] had started, so no `EnginePlayer` was bound and
 * `player?.speak(text)` quietly did nothing. The outcome makes every way the
 * request can end explicit so the UI can say *why* nothing is playing.
 */
enum class SpeakOutcome {
    /** The utterance is playing ([PlaybackController.recapPlayback] is Speaking). */
    Started,

    /** The engine is bound but no voice could be activated (none installed / picked). */
    NoVoice,

    /** The playback service never bound an engine within the timeout, or could not start. */
    EngineUnavailable,

    /** A newer utterance or a [PlaybackController.stopSpeaking] replaced this one before it began. */
    Superseded,

    /** Nothing to say (blank text). */
    Blank,
}

/**
 * Issue #1776 — the one-slot queue that holds a read-aloud request while the
 * playback service is still starting.
 *
 * Every request takes a [enqueue] ticket; the latest ticket owns the slot.
 * When the engine binds, the waiter [claim]s its ticket — only the owner gets
 * `true` and speaks, so two taps during a cold start produce one utterance
 * (the newest), and a Stop tap ([cancel]) during the wait produces none.
 * A waiter that gives up (timeout, cancellation) calls [abandon], which only
 * clears the slot if it still owns it.
 *
 * Pure and thread-safe; no Android or coroutine dependencies so the rules are
 * unit-testable on the JVM.
 */
class PendingUtteranceGate {
    private var nextTicket = 0L
    private var owner: Long? = null

    /** Reserve the slot for a new request; supersedes any request still waiting. */
    @Synchronized
    fun enqueue(): Long {
        nextTicket += 1
        owner = nextTicket
        return nextTicket
    }

    /** True iff [ticket] still owns the slot; releases it on success. */
    @Synchronized
    fun claim(ticket: Long): Boolean {
        if (owner != ticket) return false
        owner = null
        return true
    }

    /** Give up on [ticket] (timeout / cancellation). No-op if it was superseded. */
    @Synchronized
    fun abandon(ticket: Long) {
        if (owner == ticket) owner = null
    }

    /** Drop whatever request is waiting (user tapped Stop). */
    @Synchronized
    fun cancel() {
        owner = null
    }

    /** True while a request is waiting for the engine. */
    @get:Synchronized
    val hasPending: Boolean get() = owner != null

    companion object {
        /**
         * How long a request waits for the service to bind an engine. Binding
         * happens in `StoryvoxPlaybackService.onCreate`, normally well under a
         * second after the start intent; voice *loading* happens after the
         * bind and is not counted against this.
         */
        const val DEFAULT_BIND_TIMEOUT_MS: Long = 10_000L

        /**
         * Whether the caller must start the playback service before speaking.
         * Only when no engine is bound: re-sending a start-foreground intent to
         * a running-but-demoted service would re-arm the 5 s startForeground
         * deadline that the service only honours on its first start.
         */
        fun needsServiceStart(engineBound: Boolean): Boolean = !engineBound
    }
}

/**
 * Issue #1776 — the queue-until-bound algorithm behind
 * [DefaultPlaybackController.speakTextAwaitingEngine], generic over the
 * player type so it runs on the JVM with a fake.
 *
 * Takes a ticket, waits (up to [bindTimeoutMs]) for [boundPlayer] to hold a
 * player — immediately if one is already bound — then speaks only if the
 * ticket still owns [gate]. The ticket is released on every exit path,
 * including cancellation of the caller.
 */
suspend fun <P : Any> awaitEngineAndSpeak(
    text: String,
    gate: PendingUtteranceGate,
    boundPlayer: StateFlow<P?>,
    bindTimeoutMs: Long,
    speak: suspend (P, String) -> Boolean,
): SpeakOutcome {
    if (text.isBlank()) return SpeakOutcome.Blank
    val ticket = gate.enqueue()
    var claimed = false
    try {
        val p = boundPlayer.value
            ?: withTimeoutOrNull(bindTimeoutMs) { boundPlayer.filterNotNull().first() }
            ?: return SpeakOutcome.EngineUnavailable
        claimed = gate.claim(ticket)
        if (!claimed) return SpeakOutcome.Superseded
        return if (speak(p, text)) SpeakOutcome.Started else SpeakOutcome.NoVoice
    } finally {
        if (!claimed) gate.abandon(ticket)
    }
}
