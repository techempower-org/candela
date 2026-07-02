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
 * epic/plugin-dx B2 prep — pins the CURRENT EnginePlayer streaming-dispatch
 * behavior (Tier 3 #88 / #119 pools, #803 thermal governor, #1233
 * auto-language serial, Azure lookahead) so the B2 StreamingSynth inversion
 * can be proven byte-equivalent against these decisions.
 */
class StreamingDispatchTest {

    @Test fun `only Piper Kokoro Kitten build native pools`() {
        assertTrue(StreamingDispatch.buildsNativePool(EngineType.Piper.toEngineKey()))
        assertTrue(StreamingDispatch.buildsNativePool(EngineType.Kokoro(3).toEngineKey()))
        assertTrue(StreamingDispatch.buildsNativePool(EngineType.Kitten(1).toEngineKey()))
        // Supertonic runs serial (#1114 — four ONNX graphs per session).
        assertFalse(StreamingDispatch.buildsNativePool(EngineType.Supertonic(2).toEngineKey()))
        // Azure fans out synthetic lookahead handles, not native instances.
        assertFalse(StreamingDispatch.buildsNativePool(EngineType.Azure("v", "r").toEngineKey()))
        // System TTS is serialized by the framework (#676).
        assertFalse(
            StreamingDispatch.buildsNativePool(
                EngineType.SystemTts("com.google.android.tts", "en-us-x-iol-network").toEngineKey(),
            ),
        )
        assertFalse(StreamingDispatch.buildsNativePool(EngineKey("voice_martian")))
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

    @Test fun `secondary construction caps at the first load failure`() {
        assertEquals(2, StreamingDispatch.achievedSecondaries(listOf(true, true, false, true)))
        assertEquals(0, StreamingDispatch.achievedSecondaries(listOf(false, true, true)))
        assertEquals(3, StreamingDispatch.achievedSecondaries(listOf(true, true, true)))
        assertEquals(0, StreamingDispatch.achievedSecondaries(emptyList()))
    }

    @Test fun `voice-swap teardown ordering is pinned`() {
        // #89 / #1383 / #1386 — stop-pipeline first (idle instances before
        // destroy), own stale pool destroyed strictly before the rebuild.
        assertEquals(
            listOf(
                StreamingDispatch.SwapStep.STOP_PIPELINE,
                StreamingDispatch.SwapStep.DESTROY_OTHER_FAMILY_POOLS,
                StreamingDispatch.SwapStep.CONFIGURE_AND_LOAD_PRIMARY,
                StreamingDispatch.SwapStep.DESTROY_OWN_STALE_POOL,
                StreamingDispatch.SwapStep.BUILD_SECONDARIES,
            ),
            StreamingDispatch.swapStepOrder(),
        )
        assertTrue(
            StreamingDispatch.swapStepOrder().indexOf(StreamingDispatch.SwapStep.DESTROY_OWN_STALE_POOL) <
                StreamingDispatch.swapStepOrder().indexOf(StreamingDispatch.SwapStep.BUILD_SECONDARIES),
        )
    }

    @Test fun `swap preamble tears down every pooled family except the target's own`() {
        assertEquals(
            setOf(VoiceFamilyIds.KOKORO, VoiceFamilyIds.KITTEN),
            StreamingDispatch.preambleTeardownFamilies(EngineType.Piper.toEngineKey()),
        )
        assertEquals(
            setOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KITTEN),
            StreamingDispatch.preambleTeardownFamilies(EngineType.Kokoro(0).toEngineKey()),
        )
        assertEquals(
            setOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            StreamingDispatch.preambleTeardownFamilies(EngineType.Kitten(0).toEngineKey()),
        )
        // Non-pooled targets free all three.
        assertEquals(
            StreamingDispatch.NATIVE_POOL_FAMILIES,
            StreamingDispatch.preambleTeardownFamilies(EngineType.Supertonic(0).toEngineKey()),
        )
        assertEquals(
            StreamingDispatch.NATIVE_POOL_FAMILIES,
            StreamingDispatch.preambleTeardownFamilies(EngineType.Azure("v", "r").toEngineKey()),
        )
    }
}
