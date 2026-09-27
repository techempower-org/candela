package `in`.jphe.storyvox.playback

import android.media.AudioManager
import `in`.jphe.storyvox.playback.AudioFocusEvent.Abandoned
import `in`.jphe.storyvox.playback.AudioFocusEvent.Change
import `in`.jphe.storyvox.playback.AudioFocusEvent.RequestResult
import `in`.jphe.storyvox.playback.AudioFocusStatus.Denied
import `in`.jphe.storyvox.playback.AudioFocusStatus.Held
import `in`.jphe.storyvox.playback.AudioFocusStatus.Lost
import `in`.jphe.storyvox.playback.AudioFocusStatus.LostTransient
import `in`.jphe.storyvox.playback.AudioFocusStatus.NotRequested
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1769 — the audio-focus state machine as pure functions. The
 * framework constants are compile-time ints, so this is a plain JVM test.
 *
 * The bug: "not held" was read as "taken by another app", so the
 * "Paused for a call" card showed through every voice warm-up.
 */
class AudioFocusStateTest {

    private fun run(vararg events: AudioFocusEvent): AudioFocusStatus =
        events.fold(NotRequested) { s, e -> nextFocusStatus(s, e) }

    private val granted = RequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    private val failed = RequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    @Test
    fun `fresh state is not a loss`() {
        assertEquals(NotRequested, run())
        assertFalse("the #1769 bug: never-requested read as lost", NotRequested.isTakenByAnotherApp())
    }

    @Test
    fun `granted request holds focus`() {
        assertEquals(Held, run(granted))
        assertFalse(Held.isTakenByAnotherApp())
    }

    @Test
    fun `refused request is a real loss`() {
        assertEquals(Denied, run(failed))
        assertTrue(Denied.isTakenByAnotherApp())
        assertEquals(Denied, run(RequestResult(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)))
        assertEquals(Denied, run(RequestResult(12345)))
    }

    @Test
    fun `transient loss then gain returns to held`() {
        val lost = run(granted, Change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(LostTransient, lost)
        assertTrue(lost.isTakenByAnotherApp())
        assertEquals(Held, nextFocusStatus(lost, Change(AudioManager.AUDIOFOCUS_GAIN)))
    }

    @Test
    fun `permanent loss is a loss`() {
        val lost = run(granted, Change(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(Lost, lost)
        assertTrue(lost.isTakenByAnotherApp())
    }

    @Test
    fun `duck leaves the state unchanged`() {
        assertEquals(Held, run(granted, Change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)))
    }

    @Test
    fun `re-acquire after a loss clears it`() {
        assertEquals(Held, run(granted, Change(AudioManager.AUDIOFOCUS_LOSS), granted))
        assertEquals(Held, run(failed, granted))
    }

    @Test
    fun `abandon always returns to not-requested`() {
        assertEquals(NotRequested, run(granted, Abandoned))
        assertEquals(NotRequested, run(failed, Abandoned))
        assertEquals(NotRequested, run(granted, Change(AudioManager.AUDIOFOCUS_LOSS), Abandoned))
    }

    @Test
    fun `stale change after abandon is ignored`() {
        // The framework can queue a callback before our abandon lands.
        assertEquals(NotRequested, run(granted, Abandoned, Change(AudioManager.AUDIOFOCUS_LOSS)))
        assertEquals(NotRequested, run(granted, Abandoned, Change(AudioManager.AUDIOFOCUS_GAIN)))
        assertEquals(NotRequested, run(Change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)))
    }

    @Test
    fun `pause only on a real loss of a live request`() {
        assertTrue(shouldPauseOnFocusChange(Held, AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(shouldPauseOnFocusChange(Held, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertFalse(shouldPauseOnFocusChange(Held, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(shouldPauseOnFocusChange(Held, AudioManager.AUDIOFOCUS_GAIN))
        assertFalse(
            "a stale loss after our own abandon must not pause",
            shouldPauseOnFocusChange(NotRequested, AudioManager.AUDIOFOCUS_LOSS),
        )
    }
}
