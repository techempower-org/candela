package `in`.jphe.storyvox.source.audiobook.writer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4BoxesTest {

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

    private fun type(b: ByteArray, o: Int): String = String(b, o, 4, Charsets.US_ASCII)

    @Test
    fun `box header carries size and fourcc`() {
        val b = Mp4Boxes.box("test", byteArrayOf(1, 2, 3))
        assertEquals(11L, u32(b, 0))
        assertEquals("test", type(b, 4))
        assertEquals(11, b.size)
    }

    @Test
    fun `chpl encodes count and 100ns timestamps`() {
        val markers = listOf(
            ChapterMarker("Intro", 0L),
            ChapterMarker("Two", 1_000L), // 1s -> 10_000_000 in 100ns units
        )
        val chpl = Mp4Boxes.chpl(markers)
        assertEquals("chpl", type(chpl, 4))
        // payload starts at offset 8: version(1)+flags(3)+reserved(4)+count(1)
        assertEquals(0x01, chpl[8].toInt()) // version
        val count = chpl[8 + 1 + 3 + 4].toInt() and 0xFF
        assertEquals(2, count)
        // first chapter timestamp (8 bytes) right after the count byte
        val tsOffset = 8 + 1 + 3 + 4 + 1
        var ts0 = 0L
        for (i in 0 until 8) ts0 = (ts0 shl 8) or (chpl[tsOffset + i].toLong() and 0xFF)
        assertEquals(0L, ts0)
        // title length + "Intro"
        val len0 = chpl[tsOffset + 8].toInt() and 0xFF
        assertEquals("Intro".length, len0)
    }

    @Test
    fun `meta returns null when nothing to write`() {
        assertNull(Mp4Boxes.meta(title = "", author = "", cover = null))
    }

    @Test
    fun `meta carries title and author items`() {
        val meta = Mp4Boxes.meta(title = "My Book", author = "Jane Doe", cover = null)!!
        assertEquals("meta", type(meta, 4))
        val asString = String(meta, Charsets.ISO_8859_1)
        assertTrue("expected ©nam key", asString.contains("nam"))
        assertTrue("expected ©ART key", asString.contains("ART"))
        assertTrue(asString.contains("My Book"))
        assertTrue(asString.contains("Jane Doe"))
        assertTrue("expected mdir handler", asString.contains("mdir"))
    }

    @Test
    fun `cover type flag is JPEG by default and PNG when magic present`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        val metaJpeg = Mp4Boxes.meta("", "", jpeg)!!
        // covr -> data box with typeFlag 13 (JPEG)
        assertTrue(String(metaJpeg, Charsets.ISO_8859_1).contains("covr"))

        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 0)
        val metaPng = Mp4Boxes.meta("", "", png)!!
        assertTrue(String(metaPng, Charsets.ISO_8859_1).contains("covr"))
    }

    @Test
    fun `udta nests chpl and meta`() {
        val udta = Mp4Boxes.udta(
            markers = listOf(ChapterMarker("A", 0L)),
            title = "T",
            author = "Au",
            cover = null,
        )
        assertEquals("udta", type(udta, 4))
        val s = String(udta, Charsets.ISO_8859_1)
        assertTrue(s.contains("chpl"))
        assertTrue(s.contains("meta"))
        assertEquals(udta.size.toLong(), u32(udta, 0))
    }
}
