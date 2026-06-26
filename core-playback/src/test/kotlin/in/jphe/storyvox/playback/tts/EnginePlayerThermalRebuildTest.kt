package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.ThermalMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1126 — pure-function unit test for [crossedThermalModerateBoundary],
 * the boundary check that gates whether a thermal-status transition is worth
 * logging (and, pre-#1126, used to trigger a mid-playback pipeline rebuild).
 *
 * The bug: [EnginePlayer.observeThermalStatus] used to call
 * `startPlaybackPipeline()` immediately whenever the thermal status crossed
 * the MODERATE boundary while playing. A pipeline rebuild tears the
 * AudioTrack down and restarts the producer at the current sentence's start,
 * so the listener heard a brief pause followed by the current sentence
 * replaying from the beginning. Thermal crossings are the only non-user,
 * intermittent rebuild trigger (speed/pitch/pause-mult/seek/voice-swap are
 * all user-initiated), which is why #1126 reproduced as an *occasional*
 * pause-then-repeat during otherwise-normal playback — and could repeat on
 * every oscillation across the MODERATE line.
 *
 * The fix defers the parallel-synth concurrency cap to the next natural
 * pipeline boundary (the cap is read fresh from `cachedThermalStatus` at
 * `startPlaybackPipeline()` construction time, so nothing audible is lost).
 * This test pins the boundary math the deferral logging depends on.
 *
 * Like [EnginePlayerAutoPlayDecisionTest] this deliberately stays at the
 * pure-function layer; a full EnginePlayer needs a Hilt graph + sherpa-onnx
 * AARs and a real AudioTrack.
 *
 * MODERATE = 2. LIGHT (1) and CRITICAL (4) are not exposed as constants on
 * [ThermalMonitor], so the raw PowerManager-mirroring ints are used directly.
 */
class EnginePlayerThermalRebuildTest {

    private val none = ThermalMonitor.THERMAL_STATUS_NONE       // 0
    private val light = 1                                       // THERMAL_STATUS_LIGHT
    private val moderate = ThermalMonitor.THERMAL_STATUS_MODERATE // 2
    private val severe = ThermalMonitor.THERMAL_STATUS_SEVERE   // 3
    private val critical = 4                                    // THERMAL_STATUS_CRITICAL

    @Test
    fun `crossing up into MODERATE is a boundary crossing`() {
        assertTrue(crossedThermalModerateBoundary(light, moderate))
        assertTrue(crossedThermalModerateBoundary(none, moderate))
        assertTrue(crossedThermalModerateBoundary(none, severe))
        assertTrue(crossedThermalModerateBoundary(light, critical))
    }

    @Test
    fun `dropping back below MODERATE is a boundary crossing`() {
        assertTrue(crossedThermalModerateBoundary(moderate, light))
        assertTrue(crossedThermalModerateBoundary(severe, none))
        assertTrue(crossedThermalModerateBoundary(critical, light))
        assertTrue(crossedThermalModerateBoundary(moderate, none))
    }

    @Test
    fun `transitions that stay below MODERATE are NOT crossings`() {
        // No concurrency change — both sides run the user's full parallel set.
        assertFalse(crossedThermalModerateBoundary(none, light))
        assertFalse(crossedThermalModerateBoundary(light, none))
    }

    @Test
    fun `transitions that stay at-or-above MODERATE are NOT crossings`() {
        // Already capped to serial; SEVERE -> CRITICAL etc. change nothing
        // about the serial/parallel decision, so no log, no (deferred) action.
        assertFalse(crossedThermalModerateBoundary(moderate, severe))
        assertFalse(crossedThermalModerateBoundary(severe, critical))
        assertFalse(crossedThermalModerateBoundary(critical, moderate))
        assertFalse(crossedThermalModerateBoundary(severe, moderate))
    }

    @Test
    fun `MODERATE itself is on the capped side of the boundary`() {
        // The cap fires at >= MODERATE, so MODERATE counts as "throttled".
        // Entering it from below crosses; leaving it downward crosses;
        // staying at-or-above does not.
        assertTrue(crossedThermalModerateBoundary(light, moderate))   // below -> MODERATE
        assertTrue(crossedThermalModerateBoundary(moderate, light))   // MODERATE -> below
        assertFalse(crossedThermalModerateBoundary(moderate, moderate)) // no change
    }
}
