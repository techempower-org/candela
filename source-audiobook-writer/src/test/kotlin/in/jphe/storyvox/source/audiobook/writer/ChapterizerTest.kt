package `in`.jphe.storyvox.source.audiobook.writer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterizerTest {

    @Test
    fun `blank input yields no chapters`() {
        assertTrue(Chapterizer.chapterize("   \n\n  ", "Book").isEmpty())
    }

    @Test
    fun `single paragraph becomes one chapter titled after the book`() {
        val chapters = Chapterizer.chapterize("Just a short article here.", "My Article")
        assertEquals(1, chapters.size)
        assertEquals("My Article", chapters[0].title)
        assertEquals("Just a short article here.", chapters[0].text)
    }

    @Test
    fun `explicit Chapter headings split and become titles`() {
        val text = """
            Chapter 1
            The first part.

            Chapter 2
            The second part.

            Chapter 3
            The third part.
        """.trimIndent()
        val chapters = Chapterizer.chapterize(text, "Novel")
        assertEquals(3, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("The first part.", chapters[0].text)
        assertEquals("Chapter 3", chapters[2].title)
        assertEquals("The third part.", chapters[2].text)
    }

    @Test
    fun `chapter heading with subtitle keeps the whole line as the title`() {
        val text = """
            Chapter One: The Beginning
            Once upon a time.

            Chapter Two: The Middle
            Things happened.
        """.trimIndent()
        val chapters = Chapterizer.chapterize(text, "Tale")
        assertEquals(2, chapters.size)
        assertEquals("Chapter One: The Beginning", chapters[0].title)
        assertEquals("Chapter Two: The Middle", chapters[1].title)
    }

    @Test
    fun `preamble before first heading is kept as an Introduction`() {
        val text = """
            A dedication to my readers.

            Chapter 1
            Body one.

            Chapter 2
            Body two.
        """.trimIndent()
        val chapters = Chapterizer.chapterize(text, "Book")
        assertEquals(3, chapters.size)
        assertEquals("Introduction", chapters[0].title)
        assertEquals("A dedication to my readers.", chapters[0].text)
    }

    @Test
    fun `roman numeral chapters are detected`() {
        val text = """
            Chapter IV
            Four.

            Chapter V
            Five.
        """.trimIndent()
        val chapters = Chapterizer.chapterize(text, "Book")
        assertEquals(2, chapters.size)
        assertEquals("Chapter IV", chapters[0].title)
    }

    @Test
    fun `markdown headings split when at least two are present`() {
        val text = """
            # Prologue
            In the beginning.

            # Epilogue
            And in the end.
        """.trimIndent()
        val chapters = Chapterizer.chapterize(text, "Book")
        assertEquals(2, chapters.size)
        assertEquals("Prologue", chapters[0].title)
        assertEquals("Epilogue", chapters[1].title)
    }

    @Test
    fun `single markdown heading does not split into a heading-only chapter`() {
        // Only one '#' heading -> not a multi-chapter structure -> single chapter.
        val text = "# Title\nThe body of a single titled article."
        val chapters = Chapterizer.chapterize(text, "Fallback Title")
        assertEquals(1, chapters.size)
        assertEquals("Fallback Title", chapters[0].title)
    }

    @Test
    fun `horizontal rule breaks split into numbered chapters`() {
        val text = """
            First section text.

            ---

            Second section text.

            ***

            Third section text.
        """.trimIndent()
        val chapters = Chapterizer.chapterize(text, "Book")
        assertEquals(3, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("First section text.", chapters[0].text)
        assertEquals("Third section text.", chapters[2].text)
    }

    @Test
    fun `form feed splits chapters`() {
        val text = "Page one body.\n\u000CPage two body."
        val chapters = Chapterizer.chapterize(text, "Book")
        assertEquals(2, chapters.size)
        assertEquals("Page one body.", chapters[0].text)
        assertEquals("Page two body.", chapters[1].text)
    }

    @Test
    fun `CRLF line endings are normalized`() {
        val text = "Chapter 1\r\nBody one.\r\n\r\nChapter 2\r\nBody two."
        val chapters = Chapterizer.chapterize(text, "Book")
        assertEquals(2, chapters.size)
        assertEquals("Body one.", chapters[0].text)
    }
}
