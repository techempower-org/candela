package `in`.jphe.storyvox.source.audiobook.writer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookFileNameTest {

    @Test
    fun `slugifies title and appends stamp and extension`() {
        val name = AudiobookFileName.forTitle("My Great Book!", "20260605-1430")
        assertEquals("my-great-book-20260605-1430.m4b", name)
    }

    @Test
    fun `blank title falls back to audiobook`() {
        val name = AudiobookFileName.forTitle("   ", "20260605-1430")
        assertEquals("audiobook-20260605-1430.m4b", name)
    }

    @Test
    fun `mp3 fallback extension`() {
        val name = AudiobookFileName.forTitle("Tale", "20260605-1430", extension = "mp3")
        assertTrue(name.endsWith(".mp3"))
    }

    @Test
    fun `long titles are truncated to a sane slug length`() {
        val long = "a".repeat(200)
        val name = AudiobookFileName.forTitle(long, "20260605-1430")
        // 60-char slug + "-" + stamp + ".m4b"
        assertTrue(name.startsWith("a".repeat(60) + "-"))
    }
}
