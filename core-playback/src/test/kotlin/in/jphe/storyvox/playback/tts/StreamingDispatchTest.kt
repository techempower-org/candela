package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.ThermalMonitor
import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.toEngineKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * epic/plugin-dx B2 prep — pins EnginePlayer's streaming-dispatch decisions
 * (pool sizing, the #1501 family guard, #803 thermal governor, #1233
 * auto-language serial, Azure lookahead). The swap ORDER lives in
 * `StreamingPoolLifecycle.swapTo` and is pinned by `StreamingSynthTest`.
 */
class StreamingDispatchTest {

    @Test fun `the pool serves only the engine that built it`() {
        // #1501 — the family guard, now the production path in EnginePlayer.
        val kokoro = EngineType.Kokoro(3).toEngineKey()
        assertTrue(StreamingDispatch.poolServes(VoiceFamilyIds.KOKORO, kokoro))
        // A stale pool from another family must degrade to serial.
        assertFalse(StreamingDispatch.poolServes(VoiceFamilyIds.PIPER, kokoro))
        // No pool resident / no active engine.
        assertFalse(StreamingDispatch.poolServes(null, kokoro))
        assertFalse(StreamingDispatch.poolServes(VoiceFamilyIds.KOKORO, null))
        // A de-sealed plugin engine is guarded by its own id.
        assertTrue(StreamingDispatch.poolServes("voice_whisper", EngineKey("voice_whisper", 1)))
        assertFalse(StreamingDispatch.poolServes("voice_whisper", EngineKey("voice_other")))
    }

    @Test fun `pool is primary plus N minus 1 secondaries`() {
        // Slider default 1 = serial: no secondaries.
        assertEquals(0, StreamingDispatch.desiredSecondaryCount(1))
        assertEquals(1, StreamingDispatch.desiredSecondaryCount(2))
        assertEquals(7, StreamingDispatch.desiredSecondaryCount(8))
        // Defensive: never negative.
        assertEquals(0, StreamingDispatch.desiredSecondaryCount(0))
    }

    @Test fun `azure lookahead reuses the same slider derivation`() {
        assertEquals(0, StreamingDispatch.azureLookaheadCount(1))
        assertEquals(3, StreamingDispatch.azureLookaheadCount(4))
        assertEquals(0, StreamingDispatch.azureLookaheadCount(0))
    }

    @Test fun `thermal MODERATE and above forces serial only when a pool exists`() {
        val none = ThermalMonitor.THERMAL_STATUS_NONE
        // ThermalMonitor doesn't mint a LIGHT constant (only NONE/MODERATE/
        // SEVERE); PowerManager.THERMAL_STATUS_LIGHT == 1.
        val light = 1
        val moderate = ThermalMonitor.THERMAL_STATUS_MODERATE
        val severe = ThermalMonitor.THERMAL_STATUS_SEVERE
        assertFalse(StreamingDispatch.thermalForcesSerial(none, poolNonEmpty = true))
        assertFalse(StreamingDispatch.thermalForcesSerial(light, poolNonEmpty = true))
        assertTrue(StreamingDispatch.thermalForcesSerial(moderate, poolNonEmpty = true))
        assertTrue(StreamingDispatch.thermalForcesSerial(severe, poolNonEmpty = true))
        // Serial already — nothing to drop.
        assertFalse(StreamingDispatch.thermalForcesSerial(severe, poolNonEmpty = false))
    }

    @Test fun `auto-language routing forces serial only for Kokoro pools`() {
        val kokoro = EngineType.Kokoro(3).toEngineKey()
        val piper = EngineType.Piper.toEngineKey()
        assertTrue(StreamingDispatch.autoLangForcesSerial(true, kokoro, poolNonEmpty = true))
        assertFalse(StreamingDispatch.autoLangForcesSerial(false, kokoro, poolNonEmpty = true))
        assertFalse(StreamingDispatch.autoLangForcesSerial(true, piper, poolNonEmpty = true))
        assertFalse(StreamingDispatch.autoLangForcesSerial(true, kokoro, poolNonEmpty = false))
    }

    @Test fun `queue depth halves at SEVERE with a floor of 2`() {
        val severe = ThermalMonitor.THERMAL_STATUS_SEVERE
        val moderate = ThermalMonitor.THERMAL_STATUS_MODERATE
        assertEquals(8, StreamingDispatch.queueDepth(8, ThermalMonitor.THERMAL_STATUS_NONE))
        // MODERATE affects the pool, not the queue.
        assertEquals(8, StreamingDispatch.queueDepth(8, moderate))
        assertEquals(4, StreamingDispatch.queueDepth(8, severe))
        assertEquals(2, StreamingDispatch.queueDepth(4, severe))
        assertEquals(2, StreamingDispatch.queueDepth(3, severe))
        assertEquals(2, StreamingDispatch.queueDepth(2, severe))
        assertEquals(1500, StreamingDispatch.queueDepth(3000, severe))
    }
}
