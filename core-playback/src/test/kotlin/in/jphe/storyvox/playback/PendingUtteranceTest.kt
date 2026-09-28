package `in`.jphe.storyvox.playback

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1776 — the pending-utterance rules behind read-aloud on a cold
 * process: queue until the playback service binds an engine, newest request
 * wins, Stop drops the queued one, and a bind that never lands times out.
 */
class PendingUtteranceTest {

    /** Stand-in for EnginePlayer: records what it was asked to say. */
    private class FakePlayer(private val hasVoice: Boolean = true) {
        val spoken = mutableListOf<String>()
        fun speak(text: String): Boolean {
            if (!hasVoice) return false
            spoken += text
            return true
        }
    }

    private val timeout = PendingUtteranceGate.DEFAULT_BIND_TIMEOUT_MS

    // ── PendingUtteranceGate ──────────────────────────────────────────

    @Test
    fun `latest ticket owns the slot`() {
        val gate = PendingUtteranceGate()
        val first = gate.enqueue()
        val second = gate.enqueue()
        assertFalse(gate.claim(first))
        assertTrue(gate.claim(second))
        assertFalse(gate.hasPending)
    }

    @Test
    fun `a ticket can only be claimed once`() {
        val gate = PendingUtteranceGate()
        val t = gate.enqueue()
        assertTrue(gate.claim(t))
        assertFalse(gate.claim(t))
    }

    @Test
    fun `cancel drops the waiting request`() {
        val gate = PendingUtteranceGate()
        val t = gate.enqueue()
        gate.cancel()
        assertFalse(gate.hasPending)
        assertFalse(gate.claim(t))
    }

    @Test
    fun `abandoning a superseded ticket keeps the newer one`() {
        val gate = PendingUtteranceGate()
        val old = gate.enqueue()
        val new = gate.enqueue()
        gate.abandon(old)
        assertTrue(gate.hasPending)
        assertTrue(gate.claim(new))
    }

    @Test
    fun `service is started only when no engine is bound`() {
        assertTrue(PendingUtteranceGate.needsServiceStart(engineBound = false))
        assertFalse(PendingUtteranceGate.needsServiceStart(engineBound = true))
    }

    // ── awaitEngineAndSpeak ───────────────────────────────────────────

    @Test
    fun `speaks immediately when an engine is already bound`() = runTest {
        val player = FakePlayer()
        val bound = MutableStateFlow<FakePlayer?>(player)
        val outcome = awaitEngineAndSpeak("hello", PendingUtteranceGate(), bound, timeout) { p, t -> p.speak(t) }
        assertEquals(SpeakOutcome.Started, outcome)
        assertEquals(listOf("hello"), player.spoken)
    }

    @Test
    fun `cold start queues the utterance until the engine binds`() = runTest {
        val gate = PendingUtteranceGate()
        val bound = MutableStateFlow<FakePlayer?>(null)
        val result = async { awaitEngineAndSpeak("hello", gate, bound, timeout) { p, t -> p.speak(t) } }
        runCurrent()
        assertTrue(gate.hasPending)
        advanceTimeBy(1_500L)
        val player = FakePlayer()
        bound.value = player
        assertEquals(SpeakOutcome.Started, result.await())
        assertEquals(listOf("hello"), player.spoken)
        assertFalse(gate.hasPending)
    }

    @Test
    fun `bind that never lands times out and frees the slot`() = runTest {
        val gate = PendingUtteranceGate()
        val bound = MutableStateFlow<FakePlayer?>(null)
        val outcome = awaitEngineAndSpeak("hello", gate, bound, timeout) { p, t -> p.speak(t) }
        assertEquals(SpeakOutcome.EngineUnavailable, outcome)
        assertFalse(gate.hasPending)
    }

    @Test
    fun `two taps during a cold start speak only the newest`() = runTest {
        val gate = PendingUtteranceGate()
        val bound = MutableStateFlow<FakePlayer?>(null)
        val first = async { awaitEngineAndSpeak("first", gate, bound, timeout) { p, t -> p.speak(t) } }
        runCurrent()
        val second = async { awaitEngineAndSpeak("second", gate, bound, timeout) { p, t -> p.speak(t) } }
        runCurrent()
        val player = FakePlayer()
        bound.value = player
        assertEquals(SpeakOutcome.Superseded, first.await())
        assertEquals(SpeakOutcome.Started, second.await())
        assertEquals(listOf("second"), player.spoken)
    }

    @Test
    fun `stop during a cold start means nothing is spoken on bind`() = runTest {
        val gate = PendingUtteranceGate()
        val bound = MutableStateFlow<FakePlayer?>(null)
        val result = async { awaitEngineAndSpeak("hello", gate, bound, timeout) { p, t -> p.speak(t) } }
        runCurrent()
        gate.cancel()
        val player = FakePlayer()
        bound.value = player
        assertEquals(SpeakOutcome.Superseded, result.await())
        assertTrue(player.spoken.isEmpty())
    }

    @Test
    fun `cancelling the caller releases its ticket`() = runTest {
        val gate = PendingUtteranceGate()
        val bound = MutableStateFlow<FakePlayer?>(null)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            awaitEngineAndSpeak("hello", gate, bound, timeout) { p, t -> p.speak(t) }
        }
        assertTrue(gate.hasPending)
        result.cancel()
        runCurrent()
        assertFalse(gate.hasPending)
    }

    @Test
    fun `bound engine without a voice reports NoVoice`() = runTest {
        val bound = MutableStateFlow<FakePlayer?>(FakePlayer(hasVoice = false))
        val outcome = awaitEngineAndSpeak("hello", PendingUtteranceGate(), bound, timeout) { p, t -> p.speak(t) }
        assertEquals(SpeakOutcome.NoVoice, outcome)
    }

    @Test
    fun `blank text is rejected without touching the slot`() = runTest {
        val gate = PendingUtteranceGate()
        val bound = MutableStateFlow<FakePlayer?>(null)
        assertEquals(SpeakOutcome.Blank, awaitEngineAndSpeak("   ", gate, bound, timeout) { p, t -> p.speak(t) })
        assertFalse(gate.hasPending)
    }
}
