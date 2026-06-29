package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.voice.EngineType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1383 — regression net for the two synthesis-pipeline rebuild guards
 * (see [shouldDedupeConcurrentLoad] / [shouldRebuildForSystemTtsReconnect]).
 * These pin the policy that prevents "Piper synthesis cancelled right after a
 * PCM cache MISS → intermittent fragments": a double-fired load for the same
 * chapter must not rebuild, and a system-TTS framework reconnect must not
 * rebuild while a neural/cloud voice is active.
 */
class SynthPipelineGuardsTest {

    // ── shouldDedupeConcurrentLoad — the "two MISSes 54 ms apart" door ──

    @Test
    fun `dedupes a second load for the same chapter while a pipeline is live`() {
        // The exact #1383 double-fire: already playing chapter 3609400, a
        // near-simultaneous re-trigger for the SAME chapter must be a no-op.
        assertTrue(
            shouldDedupeConcurrentLoad(
                armedFictionId = "f1",
                armedChapterId = "3609400",
                requestedFictionId = "f1",
                requestedChapterId = "3609400",
                pipelineRunning = true,
            ),
        )
    }

    @Test
    fun `does NOT dedupe when no pipeline is running yet — cold start must proceed`() {
        assertFalse(
            shouldDedupeConcurrentLoad(
                armedFictionId = "f1",
                armedChapterId = "3609400",
                requestedFictionId = "f1",
                requestedChapterId = "3609400",
                pipelineRunning = false,
            ),
        )
    }

    @Test
    fun `does NOT dedupe a genuine navigation to a different chapter`() {
        assertFalse(
            shouldDedupeConcurrentLoad(
                armedFictionId = "f1",
                armedChapterId = "3609400",
                requestedFictionId = "f1",
                requestedChapterId = "3609401",
                pipelineRunning = true,
            ),
        )
    }

    @Test
    fun `does NOT dedupe a load for a different fiction`() {
        assertFalse(
            shouldDedupeConcurrentLoad(
                armedFictionId = "f1",
                armedChapterId = "3609400",
                requestedFictionId = "f2",
                requestedChapterId = "3609400",
                pipelineRunning = true,
            ),
        )
    }

    @Test
    fun `does NOT dedupe when nothing is armed yet`() {
        // First-ever load: armed ids are null, so there is nothing to dedupe
        // against even if a stale running flag lingered.
        assertFalse(
            shouldDedupeConcurrentLoad(
                armedFictionId = null,
                armedChapterId = null,
                requestedFictionId = "f1",
                requestedChapterId = "3609400",
                pipelineRunning = true,
            ),
        )
    }

    // ── shouldRebuildForSystemTtsReconnect — the voice-event gating door ──

    @Test
    fun `rebuilds on a system-TTS reconnect only when a system voice is active`() {
        assertTrue(
            shouldRebuildForSystemTtsReconnect(
                EngineType.SystemTts(engineName = "com.google.android.tts", voiceName = "en-us-x-iol"),
            ),
        )
    }

    @Test
    fun `does NOT rebuild for Piper — the #1383 case`() {
        // Piper renders through sherpa-onnx, not the framework TextToSpeech;
        // a system-TTS reconnect must not tear its producer down mid-MISS.
        assertFalse(shouldRebuildForSystemTtsReconnect(EngineType.Piper))
    }

    @Test
    fun `does NOT rebuild for Kokoro Kitten or Supertonic neural engines`() {
        assertFalse(shouldRebuildForSystemTtsReconnect(EngineType.Kokoro(speakerId = 0)))
        assertFalse(shouldRebuildForSystemTtsReconnect(EngineType.Kitten(speakerId = 3)))
        assertFalse(shouldRebuildForSystemTtsReconnect(EngineType.Supertonic(speakerId = 1)))
    }

    @Test
    fun `does NOT rebuild for a cloud Azure voice`() {
        assertFalse(
            shouldRebuildForSystemTtsReconnect(
                EngineType.Azure(voiceName = "en-US-AvaMultilingualNeural", region = "eastus"),
            ),
        )
    }
}
