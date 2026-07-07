package `in`.jphe.storyvox.playback.transcribe.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice Notes (#1657, Phase 2b) — pure window arithmetic for chunked
 * transcription (bounded memory). No sherpa / MediaCodec — the device parts.
 */
class TranscriptionChunkerTest {

    @Test
    fun empty_forNonPositiveInputs() {
        assertTrue(TranscriptionChunker.windows(0).isEmpty())
        assertTrue(TranscriptionChunker.windows(-5).isEmpty())
        assertTrue(TranscriptionChunker.windows(16_000, sampleRate = 0).isEmpty())
        assertTrue(TranscriptionChunker.windows(16_000, windowSec = 0).isEmpty())
    }

    @Test
    fun exactMultiple_producesFullContiguousWindows() {
        val w = TranscriptionChunker.windows(totalSamples = 48_000, sampleRate = 16_000, windowSec = 1)
        assertEquals(3, w.size)
        assertEquals(listOf(0, 16_000, 32_000), w.map { it.startSample })
        assertEquals(listOf(16_000, 32_000, 48_000), w.map { it.endSample })
        assertEquals(listOf(0L, 1000L, 2000L), w.map { it.startMs })
        assertEquals(listOf(1000L, 2000L, 3000L), w.map { it.endMs })
    }

    @Test
    fun remainder_lastWindowIsPartial_andEndMsClampedToRealLength() {
        val w = TranscriptionChunker.windows(totalSamples = 40_000, sampleRate = 16_000, windowSec = 1)
        assertEquals(3, w.size)
        assertEquals(32_000, w.last().startSample)
        assertEquals(40_000, w.last().endSample)
        assertEquals(2500L, w.last().endMs) // 40000 / 16000 s = 2.5 s, not a full 3 s
    }

    @Test
    fun singleShortWindow_smallerThanWindowSize() {
        val w = TranscriptionChunker.windows(totalSamples = 8_000, sampleRate = 16_000, windowSec = 30)
        assertEquals(1, w.size)
        assertEquals(0, w.first().startSample)
        assertEquals(8_000, w.first().endSample)
        assertEquals(0L, w.first().startMs)
        assertEquals(500L, w.first().endMs)
    }

    @Test
    fun windowsCoverEverythingContiguously() {
        val w = TranscriptionChunker.windows(100_000, 16_000, 1)
        assertEquals(0, w.first().startSample)
        assertEquals(100_000, w.last().endSample)
        for (i in 1 until w.size) {
            assertEquals("window $i starts where ${i - 1} ends", w[i - 1].endSample, w[i].startSample)
        }
    }
}
