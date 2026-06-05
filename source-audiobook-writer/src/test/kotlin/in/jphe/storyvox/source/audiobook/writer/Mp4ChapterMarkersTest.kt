package `in`.jphe.storyvox.source.audiobook.writer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Mp4ChapterMarkersTest {

    @Test
    fun `markers accumulate start offsets from durations`() {
        val markers = Mp4ChapterMarkers.markers(
            titles = listOf("One", "Two", "Three"),
            durationsMs = listOf(5_000L, 3_000L, 10_000L),
        )
        assertEquals(3, markers.size)
        assertEquals(0L, markers[0].startMs)
        assertEquals(5_000L, markers[1].startMs)
        assertEquals(8_000L, markers[2].startMs)
        assertEquals("Two", markers[1].title)
    }

    @Test
    fun `single chapter starts at zero`() {
        val markers = Mp4ChapterMarkers.markers(listOf("Only"), listOf(42_000L))
        assertEquals(1, markers.size)
        assertEquals(0L, markers[0].startMs)
    }

    @Test
    fun `negative durations are clamped so offsets never go backwards`() {
        val markers = Mp4ChapterMarkers.markers(
            titles = listOf("A", "B", "C"),
            durationsMs = listOf(-1_000L, 2_000L, 1_000L),
        )
        assertEquals(0L, markers[0].startMs)
        // -1000 clamped to 0, so B still starts at 0.
        assertEquals(0L, markers[1].startMs)
        assertEquals(2_000L, markers[2].startMs)
    }

    @Test
    fun `mismatched title and duration counts throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            Mp4ChapterMarkers.markers(listOf("A", "B"), listOf(1_000L))
        }
    }

    @Test
    fun `text sample is uint16 length-prefixed UTF-8`() {
        val sample = Mp4ChapterMarkers.textSample("Hi")
        // 2-byte big-endian length (2), then 'H','i'.
        assertArrayEquals(byteArrayOf(0x00, 0x02, 'H'.code.toByte(), 'i'.code.toByte()), sample)
    }

    @Test
    fun `text sample counts UTF-8 bytes not chars`() {
        // "é" is two UTF-8 bytes (0xC3 0xA9).
        val sample = Mp4ChapterMarkers.textSample("é")
        assertEquals(0x00, sample[0].toInt())
        assertEquals(0x02, sample[1].toInt())
        assertEquals(4, sample.size)
    }
}
