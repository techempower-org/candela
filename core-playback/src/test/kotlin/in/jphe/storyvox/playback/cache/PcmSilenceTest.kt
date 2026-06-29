package `in`.jphe.storyvox.playback.cache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1281 / #1315 — boundary coverage for the PCM "is this all silence?"
 * helpers backing CacheFileSource's read-side content gate.
 *
 * The silence definition is *strictly all-zero* by default
 * ([PCM_SILENCE_TOLERANCE_BYTES] == 0): any non-zero byte is audio. These tests
 * pin the boundaries the gate relies on — the all-zero / first-non-zero edges,
 * the `len` / `scanBytes` windowing (so a trailing non-zero outside the scanned
 * region is correctly ignored), signed (high-bit) bytes counting as non-zero,
 * the tolerance knob, and the 64 KB multi-buffer streaming path.
 */
class PcmSilenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun pcmFile(name: String, bytes: ByteArray): File =
        tempFolder.newFile(name).apply { writeBytes(bytes) }

    // ── isPcmBufferSilent (pure) ──────────────────────────────────────────

    @Test fun `empty buffer is silent`() {
        assertTrue(isPcmBufferSilent(ByteArray(0)))
    }

    @Test fun `all-zero buffer is silent`() {
        assertTrue(isPcmBufferSilent(ByteArray(1024)))
    }

    @Test fun `a single non-zero byte makes the buffer audio`() {
        assertFalse(isPcmBufferSilent(byteArrayOf(0, 0, 1, 0)))
    }

    @Test fun `a non-zero byte at index zero is audio (early exit)`() {
        // First byte already exceeds tolerance 0 → returns false without scanning on.
        val buf = ByteArray(8192).also { it[0] = 7 }
        assertFalse(isPcmBufferSilent(buf))
    }

    @Test fun `a high-bit (negative) byte counts as non-zero`() {
        // 0xFF decodes to -1 as a signed Byte; toInt() != 0, so it's audio.
        assertFalse(isPcmBufferSilent(byteArrayOf(-1)))
    }

    @Test fun `len bounds the scan and excludes a trailing non-zero byte`() {
        val buf = byteArrayOf(0, 0, 5)
        assertTrue(isPcmBufferSilent(buf, len = 2))   // index 2 (the 5) is outside [0,2)
        assertFalse(isPcmBufferSilent(buf, len = 3))  // now the 5 is included
    }

    @Test fun `len of zero is silent even when the array holds non-zero bytes`() {
        assertTrue(isPcmBufferSilent(byteArrayOf(5, 5, 5), len = 0))
    }

    @Test fun `tolerance permits up to N non-zero bytes inclusive`() {
        // 2 non-zero bytes with tolerance 2 → still silent (not > tolerance).
        assertTrue(isPcmBufferSilent(byteArrayOf(1, 0, 2, 0), tolerance = 2))
        // 3 non-zero bytes with tolerance 2 → exceeds → audio.
        assertFalse(isPcmBufferSilent(byteArrayOf(1, 2, 3), tolerance = 2))
    }

    @Test fun `default tolerance is strictly zero`() {
        assertEquals(0, PCM_SILENCE_TOLERANCE_BYTES)
    }

    // ── pcmIsAllSilence (streams a File) ──────────────────────────────────

    @Test fun `non-positive scanBytes is silent regardless of content`() {
        val f = pcmFile("audio.pcm", byteArrayOf(1, 2, 3))
        assertTrue(pcmIsAllSilence(f, scanBytes = 0L))
        assertTrue(pcmIsAllSilence(f, scanBytes = -1L))
    }

    @Test fun `empty file is silent`() {
        val f = tempFolder.newFile("empty.pcm") // 0 bytes
        assertTrue(pcmIsAllSilence(f, scanBytes = 64L))
    }

    @Test fun `all-zero file within the scan is silent`() {
        val f = pcmFile("zeros.pcm", ByteArray(100))
        assertTrue(pcmIsAllSilence(f, scanBytes = 100L))
    }

    @Test fun `a non-zero byte inside the scan makes the file audio`() {
        val f = pcmFile("blip.pcm", ByteArray(100).also { it[40] = 9 })
        assertFalse(pcmIsAllSilence(f, scanBytes = 100L))
    }

    @Test fun `a non-zero byte beyond scanBytes is ignored`() {
        // 6 bytes; only the last (index 5) is non-zero.
        val f = pcmFile("tail.pcm", byteArrayOf(0, 0, 0, 0, 0, 5))
        assertTrue(pcmIsAllSilence(f, scanBytes = 5L))   // scans indices 0..4 → silent
        assertFalse(pcmIsAllSilence(f, scanBytes = 6L))  // includes index 5 → audio
    }

    @Test fun `scanBytes larger than the file scans only what exists`() {
        assertTrue(pcmIsAllSilence(pcmFile("short-zero.pcm", ByteArray(8)), scanBytes = 10_000L))
        assertFalse(pcmIsAllSilence(pcmFile("short-blip.pcm", byteArrayOf(0, 3)), scanBytes = 10_000L))
    }

    @Test fun `a multi-buffer all-zero file is silent`() {
        // > 2 × 64 KB so the streaming loop runs several buffers.
        val f = pcmFile("big-zeros.pcm", ByteArray(130_000))
        assertTrue(pcmIsAllSilence(f, scanBytes = 130_000L))
    }

    @Test fun `a non-zero byte in a later buffer is detected`() {
        val f = pcmFile("big-blip.pcm", ByteArray(130_000).also { it[70_000] = 1 })
        assertFalse(pcmIsAllSilence(f, scanBytes = 130_000L))
        // The same byte sits exactly at the scan boundary: index 70_000 is the
        // 70_001st byte, so a 70_000-byte scan (indices 0..69_999) excludes it.
        assertTrue(pcmIsAllSilence(f, scanBytes = 70_000L))
        assertFalse(pcmIsAllSilence(f, scanBytes = 70_001L))
    }
}
