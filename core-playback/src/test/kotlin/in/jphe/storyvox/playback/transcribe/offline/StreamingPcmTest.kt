package `in`.jphe.storyvox.playback.transcribe.offline

import `in`.jphe.storyvox.playback.transcribe.PcmDownsampler
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1669 — the pure halves of the streaming decode. The load-bearing property:
 * streaming over ANY chunking is sample-identical to the old whole-file
 * [PcmDownsampler.toMono16k] pass, and windowing matches [TranscriptionChunker].
 */
class StreamingPcmTest {

    private fun pcm16(frames: Int, channels: Int, seed: Int = 7): ByteArray {
        val r = Random(seed)
        return ByteArray(frames * channels * 2).also { r.nextBytes(it) }
    }

    /** Feed [bytes] split at the given chunk sizes (cycled), then flush. */
    private fun streamed(bytes: ByteArray, rate: Int, channels: Int, chunks: IntArray): FloatArray {
        val rs = StreamingMono16kResampler(rate, channels)
        val out = ArrayList<FloatArray>()
        var off = 0
        var k = 0
        while (off < bytes.size) {
            val n = minOf(chunks[k++ % chunks.size], bytes.size - off)
            out += rs.feed(bytes, off, n)
            off += n
        }
        out += rs.flush()
        val total = out.sumOf { it.size }
        val flat = FloatArray(total)
        var p = 0
        for (a in out) { a.copyInto(flat, p); p += a.size }
        return flat
    }

    private fun assertMatchesWholeFile(rate: Int, channels: Int, frames: Int, chunks: IntArray) {
        val bytes = pcm16(frames, channels, seed = rate + channels + frames)
        val expected = PcmDownsampler.toMono16k(bytes, rate, channels)
        val actual = streamed(bytes, rate, channels, chunks)
        assertEquals("length rate=$rate ch=$channels", expected.size, actual.size)
        assertArrayEquals("samples rate=$rate ch=$channels", expected, actual, 0f)
    }

    @Test
    fun downsample48kStereo_matchesWholeFile_forCodecSizedChunks() =
        assertMatchesWholeFile(48_000, 2, frames = 48_000 * 3 + 17, chunks = intArrayOf(4096))

    @Test
    fun downsample44k1Stereo_matchesWholeFile_forOddChunksThatSplitFrames() =
        // 44.1 kHz → non-integer step; odd sizes split frames and samples mid-byte.
        assertMatchesWholeFile(44_100, 2, frames = 44_100 * 2 + 3, chunks = intArrayOf(1, 3, 7, 1001, 4093, 2))

    @Test
    fun mono22k05_matchesWholeFile() =
        assertMatchesWholeFile(22_050, 1, frames = 22_050 + 5, chunks = intArrayOf(333, 2048))

    @Test
    fun upsample8kMono_matchesWholeFile() =
        assertMatchesWholeFile(8_000, 1, frames = 8_000 + 1, chunks = intArrayOf(5, 160, 999))

    @Test
    fun passthrough16kMono_matchesWholeFile() =
        assertMatchesWholeFile(16_000, 1, frames = 16_000 + 11, chunks = intArrayOf(1, 640, 4097))

    @Test
    fun sixChannel_matchesWholeFile() =
        assertMatchesWholeFile(48_000, 6, frames = 4_800, chunks = intArrayOf(11, 12, 5000))

    @Test
    fun oneByteAtATime_matchesWholeFile() =
        assertMatchesWholeFile(48_000, 2, frames = 1_200, chunks = intArrayOf(1))

    @Test
    fun singleChunk_matchesWholeFile() {
        val bytes = pcm16(9_000, 2)
        assertArrayEquals(
            PcmDownsampler.toMono16k(bytes, 48_000, 2),
            streamed(bytes, 48_000, 2, intArrayOf(bytes.size)),
            0f,
        )
    }

    @Test
    fun carryStaysBounded_outputIsEmittedIncrementally() {
        // Streaming, not buffering: each 1 s chunk yields ~1 s of output before flush.
        val rs = StreamingMono16kResampler(48_000, 2)
        val chunk = pcm16(48_000, 2)
        repeat(5) {
            val out = rs.feed(chunk)
            assertTrue("chunk $it emitted ${out.size}", out.size in 15_990..16_000)
        }
        assertTrue(rs.flush().size <= 10)
    }

    @Test
    fun degenerateInput_isEmpty() {
        assertEquals(0, StreamingMono16kResampler(0, 2).feed(ByteArray(8)).size)
        assertEquals(0, StreamingMono16kResampler(48_000, 0).feed(ByteArray(8)).size)
        val rs = StreamingMono16kResampler(48_000, 2)
        assertEquals(0, rs.feed(ByteArray(3)).size) // less than a frame
        assertEquals(0, rs.flush().size)
        assertEquals(0, rs.flush().size) // idempotent
    }

    @Test(expected = IllegalStateException::class)
    fun feedAfterFlush_throws() {
        val rs = StreamingMono16kResampler(48_000, 2)
        rs.flush()
        rs.feed(ByteArray(4))
    }

    // --- PcmWindowAccumulator ---------------------------------------------

    private fun accumulate(total: Int, windowSamples: Int, pushSizes: IntArray): List<PcmWindow> {
        val acc = PcmWindowAccumulator(windowSamples)
        val out = ArrayList<PcmWindow>()
        var sent = 0
        var k = 0
        while (sent < total) {
            val n = minOf(pushSizes[k++ % pushSizes.size], total - sent)
            out += acc.push(FloatArray(n) { (sent + it).toFloat() })
            sent += n
        }
        acc.flush()?.let { out += it }
        return out
    }

    @Test
    fun windows_matchTranscriptionChunker_boundariesAndTimes() {
        val total = 100_000
        val expected = TranscriptionChunker.windows(total, sampleRate = 16_000, windowSec = 1)
        val actual = accumulate(total, windowSamples = 16_000, pushSizes = intArrayOf(1024, 7, 40_000))
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.startSample.toLong(), a.startSample)
            assertEquals(e.endSample.toLong(), a.endSample)
            assertEquals(e.startMs, a.startMs)
            assertEquals(e.endMs, a.endMs)
        }
    }

    @Test
    fun windows_preserveSampleOrderAcrossPushes() {
        val windows = accumulate(50_000, windowSamples = 16_000, pushSizes = intArrayOf(3, 20_001))
        var expectNext = 0f
        for (w in windows) {
            assertEquals(expectNext, w.samples.first(), 0f)
            assertEquals(w.startSample.toFloat(), w.samples.first(), 0f)
            expectNext = w.samples.last() + 1f
        }
        assertEquals(50_000f, expectNext, 0f)
    }

    @Test
    fun onePushSpanningManyWindows_emitsAllOfThem() {
        val acc = PcmWindowAccumulator(windowSamples = 10)
        val out = acc.push(FloatArray(35))
        assertEquals(3, out.size)
        assertEquals(35L, acc.totalSamples)
        assertEquals(5, acc.flush()!!.samples.size)
        assertNull(acc.flush())
    }

    @Test
    fun exactMultiple_hasNoTrailingWindow() {
        val acc = PcmWindowAccumulator(windowSamples = 10)
        assertEquals(2, acc.push(FloatArray(20)).size)
        assertNull(acc.flush())
    }

    @Test
    fun emptyPush_emitsNothing() {
        val acc = PcmWindowAccumulator(windowSamples = 10)
        assertTrue(acc.push(FloatArray(0)).isEmpty())
        assertNull(acc.flush())
    }

    @Test
    fun multiHourTimestamps_doNotOverflow() {
        // 3 h at 16 kHz = 172.8 M samples; a window deep into it keeps exact ms.
        val w = PcmWindow(startSample = 16_000L * 3 * 3600, samples = FloatArray(16_000 * 30))
        assertEquals(3L * 3600 * 1000, w.startMs)
        assertEquals(3L * 3600 * 1000 + 30_000, w.endMs)
    }
}
