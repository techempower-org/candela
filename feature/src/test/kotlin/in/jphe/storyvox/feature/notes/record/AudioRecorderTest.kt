package `in`.jphe.storyvox.feature.notes.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [AudioRecorder]'s device-independent logic. The
 * `MediaRecorder`/FGS surface can't be exercised on plain JVM, so — following the
 * house "test the extracted pure decision fns" pattern — the amplitude
 * normalization and the pause-aware elapsed math are pulled into companion
 * functions and pinned here.
 */
class AudioRecorderTest {

    // ── normalizeAmplitude ────────────────────────────────────────────────────

    @Test
    fun `normalize maps silence to zero`() {
        assertEquals(0f, AudioRecorder.normalizeAmplitude(0), 0f)
        assertEquals(0f, AudioRecorder.normalizeAmplitude(-5), 0f)
    }

    @Test
    fun `normalize maps full scale to one`() {
        assertEquals(1f, AudioRecorder.normalizeAmplitude(32_767), 1e-4f)
    }

    @Test
    fun `normalize clamps above full scale to one`() {
        assertEquals(1f, AudioRecorder.normalizeAmplitude(99_999), 0f)
    }

    @Test
    fun `normalize stays within unit range and is monotonic`() {
        var prev = -1f
        for (raw in 0..32_767 step 512) {
            val v = AudioRecorder.normalizeAmplitude(raw)
            assertTrue("in [0,1] for raw=$raw (was $v)", v in 0f..1f)
            assertTrue("monotonic non-decreasing at raw=$raw", v >= prev)
            prev = v
        }
    }

    @Test
    fun `normalize applies a perceptual boost above linear`() {
        // sqrt lifts the low end: a quarter-scale reading should render taller
        // than its linear 0.25 so quiet speech is visible.
        val v = AudioRecorder.normalizeAmplitude(32_767 / 4)
        assertTrue("expected boost above linear 0.25, got $v", v > 0.25f)
    }

    // ── computeElapsedMs ──────────────────────────────────────────────────────

    @Test
    fun `elapsed while running is now minus start`() {
        val e = AudioRecorder.computeElapsedMs(
            startRealtime = 1_000L, now = 5_000L,
            pausedAccumMs = 0L, isPaused = false, pauseStartedAt = 0L,
        )
        assertEquals(4_000L, e)
    }

    @Test
    fun `elapsed subtracts accumulated paused spans`() {
        val e = AudioRecorder.computeElapsedMs(
            startRealtime = 1_000L, now = 5_000L,
            pausedAccumMs = 1_000L, isPaused = false, pauseStartedAt = 0L,
        )
        assertEquals(3_000L, e)
    }

    @Test
    fun `elapsed freezes during an open pause span`() {
        // Started at 1000, now 5000 (4000 raw), currently paused since 3000
        // (open pause = 2000) → 2000 active.
        val e = AudioRecorder.computeElapsedMs(
            startRealtime = 1_000L, now = 5_000L,
            pausedAccumMs = 0L, isPaused = true, pauseStartedAt = 3_000L,
        )
        assertEquals(2_000L, e)

        // Advancing `now` while still paused must not increase elapsed.
        val later = AudioRecorder.computeElapsedMs(
            startRealtime = 1_000L, now = 8_000L,
            pausedAccumMs = 0L, isPaused = true, pauseStartedAt = 3_000L,
        )
        assertEquals(2_000L, later)
    }

    @Test
    fun `elapsed never goes negative`() {
        val e = AudioRecorder.computeElapsedMs(
            startRealtime = 5_000L, now = 1_000L,
            pausedAccumMs = 0L, isPaused = false, pauseStartedAt = 0L,
        )
        assertEquals(0L, e)
    }
}
