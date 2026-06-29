package `in`.jphe.storyvox.playback.transcribe

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1368 — pure mic-path math: RMS loudness, PCM→float conversion, and
 * the ysProbs→confidence mapping.
 */
class AsrAudioTest {

    @Test
    fun `rms of silence is zero`() {
        assertEquals(0f, AsrAudio.rms(ShortArray(1_600), 1_600), 0f)
    }

    @Test
    fun `rms of a full-scale signal is near one`() {
        val full = ShortArray(64) { Short.MAX_VALUE }
        assertEquals(1f, AsrAudio.rms(full, full.size), 0.001f)
    }

    @Test
    fun `rms honors the length argument`() {
        // Only the first 2 (loud) samples count; the silent tail is ignored.
        val pcm = shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE, 0, 0)
        assertEquals(1f, AsrAudio.rms(pcm, 2), 0.001f)
    }

    @Test
    fun `toFloatPcm normalizes to plus-minus one`() {
        val out = AsrAudio.toFloatPcm(shortArrayOf(16_384, -16_384, 0), 3)
        assertEquals(0.5f, out[0], 0.0001f)
        assertEquals(-0.5f, out[1], 0.0001f)
        assertEquals(0f, out[2], 0f)
    }

    @Test
    fun `toFloatPcm clamps to the given length`() {
        val out = AsrAudio.toFloatPcm(shortArrayOf(1, 2, 3, 4), 2)
        assertEquals(2, out.size)
    }

    @Test
    fun `confidence is one when no probs are reported`() {
        assertEquals(1f, asrConfidence(FloatArray(0)), 0f)
    }

    @Test
    fun `confidence is the geometric mean of token probabilities`() {
        // Two tokens each at prob 0.5 (log = ln 0.5) → exp(mean(log)) = 0.5.
        val logs = floatArrayOf(ln(0.5f), ln(0.5f))
        assertEquals(0.5f, asrConfidence(logs), 0.001f)
    }

    @Test
    fun `confidence clamps above one`() {
        // A positive (impossible-for-a-prob) log value must not exceed 1.
        assertEquals(1f, asrConfidence(floatArrayOf(1f)), 0f)
    }
}
