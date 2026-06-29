package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1223 — locks down the one signal-processing step of the radio
 * STT path before the (device-validated) recognizer wiring lands:
 * decode → downmix → resample → normalise. Pure JVM, no device.
 */
class PcmDownsamplerTest {

    /** Build interleaved signed-16-bit-LE PCM from raw Int samples. */
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            b[i * 2] = (samples[i] and 0xFF).toByte()
            b[i * 2 + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun `mono 16k passes through and normalises to minus-one-to-one`() {
        val out = PcmDownsampler.toMono16k(pcm(0, 16384, -16384, 32767), 16_000, 1)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(-0.5f, out[2], 1e-4f)
        assertEquals(0.99997f, out[3], 1e-4f)
    }

    @Test
    fun `full-scale extremes map to the rails`() {
        assertEquals(-1.0f, PcmDownsampler.toMono16k(pcm(-32768), 16_000, 1)[0], 1e-6f)
        assertEquals(0f, PcmDownsampler.toMono16k(pcm(0), 16_000, 1)[0], 1e-6f)
        // +full-scale is 32767/32768 — just shy of 1.0, never clips past it.
        assertEquals(0.99997f, PcmDownsampler.toMono16k(pcm(32767), 16_000, 1)[0], 1e-4f)
    }

    @Test
    fun `stereo is downmixed by averaging the channels`() {
        // frame 0: L=16384 R=-16384 → avg 0; frame 1: L=32766 R=0 → avg 16383
        val out = PcmDownsampler.toMono16k(pcm(16384, -16384, 32766, 0), 16_000, 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0.49997f, out[1], 1e-4f)
    }

    @Test
    fun `48k downsamples to a third of the frames`() {
        val twelveFrames = pcm(*IntArray(12) { it * 100 })
        val out = PcmDownsampler.toMono16k(twelveFrames, 48_000, 1)
        assertEquals(4, out.size) // 12 * 16000 / 48000
    }

    @Test
    fun `32k downsample halves length and linearly samples`() {
        // mono ramp [0, .25, .5, .75] @ 32k → step 2.0 → picks indices 0 and 2.
        val out = PcmDownsampler.toMono16k(pcm(0, 8192, 16384, 24576), 32_000, 1)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
    }

    @Test
    fun `empty and degenerate input yield empty output`() {
        assertEquals(0, PcmDownsampler.toMono16k(ByteArray(0), 16_000, 1).size)
        assertEquals(0, PcmDownsampler.toMono16k(pcm(1, 2, 3), 16_000, 0).size)
        assertEquals(0, PcmDownsampler.toMono16k(pcm(1, 2, 3), 0, 1).size)
    }

    @Test
    fun `trailing bytes that do not complete a frame are ignored`() {
        // 3 bytes = one whole 16-bit sample (0x4000 = 16384) + one stray byte.
        val out = PcmDownsampler.toMono16k(byteArrayOf(0x00, 0x40, 0x00), 16_000, 1)
        assertEquals(1, out.size)
        assertEquals(0.5f, out[0], 1e-4f)
    }
}
