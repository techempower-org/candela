package `in`.jphe.storyvox.source.audiobook.writer

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4MetadataInjectorTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    /** Build a minimal MP4-shaped file: ftyp, then mdat, then a trailing
     *  moov (the layout MediaMuxer produces). moov has a single child so the
     *  box-walk has something to skip. */
    private fun fakeMp4(moovLast: Boolean = true): File {
        val ftyp = Mp4Boxes.box("ftyp", "isom".toByteArray())
        val mdat = Mp4Boxes.box("mdat", ByteArray(64) { it.toByte() })
        val moovChild = Mp4Boxes.box("mvhd", ByteArray(8))
        val moov = Mp4Boxes.box("moov", moovChild)
        val f = tmp.newFile("test.m4a")
        f.outputStream().use {
            it.write(ftyp)
            if (moovLast) {
                it.write(mdat)
                it.write(moov)
            } else {
                it.write(moov) // faststart: moov before mdat
                it.write(mdat)
            }
        }
        return f
    }

    @Test
    fun `finds trailing moov`() {
        val f = fakeMp4()
        RandomAccessFile(f, "r").use { raf ->
            val moov = Mp4MetadataInjector.findTopLevelMoov(raf)
            assertTrue(moov != null)
            assertEquals(raf.length(), moov!!.offset + moov.size)
        }
    }

    @Test
    fun `does not return moov when it is not last`() {
        val f = fakeMp4(moovLast = false)
        RandomAccessFile(f, "r").use { raf ->
            assertTrue(Mp4MetadataInjector.findTopLevelMoov(raf) == null)
        }
    }

    @Test
    fun `inject grows moov and appends udta, file stays well-formed`() {
        val f = fakeMp4()
        val originalLen = f.length()

        val markers = listOf(ChapterMarker("One", 0L), ChapterMarker("Two", 5_000L))
        RandomAccessFile(f, "rw").use { raf ->
            val moovBefore = Mp4MetadataInjector.findTopLevelMoov(raf)!!
            val injected = Mp4MetadataInjector.inject(
                raf, markers, title = "Title", author = "Auth", cover = null,
            )
            assertTrue(injected)

            // File grew by exactly the udta size.
            val udta = Mp4Boxes.udta(markers, "Title", "Auth", null)
            assertEquals(originalLen + udta.size, raf.length())

            // moov is still the last box and now includes udta.
            val moovAfter = Mp4MetadataInjector.findTopLevelMoov(raf)!!
            assertEquals(moovBefore.offset, moovAfter.offset)
            assertEquals(moovBefore.size + udta.size, moovAfter.size)
            assertEquals(raf.length(), moovAfter.offset + moovAfter.size)
        }

        // The trailing bytes are the udta we wrote.
        val bytes = f.readBytes()
        val tail = String(bytes, bytes.size - 200.coerceAtMost(bytes.size), 200.coerceAtMost(bytes.size), Charsets.ISO_8859_1)
        assertTrue(tail.contains("udta"))
        assertTrue(tail.contains("chpl"))
    }

    @Test
    fun `inject is a no-op when there is nothing to write`() {
        val f = fakeMp4()
        val originalLen = f.length()
        RandomAccessFile(f, "rw").use { raf ->
            val injected = Mp4MetadataInjector.inject(
                raf, markers = emptyList(), title = "", author = "", cover = null,
            )
            assertFalse(injected)
        }
        assertEquals(originalLen, f.length())
    }
}
