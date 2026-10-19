package `in`.jphe.storyvox.source.spacebattles

import `in`.jphe.storyvox.source.spacebattles.engine.XfEngineParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XfEngineParserTest {
    private val base = "https://forums.example.com"

    @Test fun `forum listing parses story threads and skips sticky rows`() {
        val r = XfEngineParser.parseForum(XfFixtures.FORUM, base)
        assertEquals(listOf("8961", "41108"), r.threads.map { it.threadId })
        val t = r.threads.first()
        assertEquals("With This Ring (Young Justice SI) (Story Only)", t.title)
        assertEquals("Mr Zoat", t.author)
        assertEquals(892, t.replies)
        assertEquals(1_760_641_554_000L, t.lastPostAt)
        assertEquals(1200, r.threads[1].replies)
        assertTrue(r.hasNext)
    }

    @Test fun `threadmarks index parses marks, title, totals and paging`() {
        val p = XfEngineParser.parseThreadmarks(XfFixtures.THREADMARKS, base)
        assertEquals("With This Ring (Young Justice SI) (Story Only)", p.title)
        assertEquals(listOf("2408634", "2408700"), p.marks.map { it.postId })
        assertEquals("Dependency Day", p.marks[0].title)
        assertEquals("Mr Zoat", p.marks[0].author)
        assertEquals(1_421_119_695_000L, p.marks[0].createdAt)
        assertEquals(1300L, p.marks[0].words)
        assertEquals(980L, p.marks[1].words)
        assertEquals(207_000L, p.totalWords)
        assertTrue(p.hasNext)
    }

    @Test fun `threadmarks last page reports no next`() {
        val p = XfEngineParser.parseThreadmarks(XfFixtures.THREADMARKS_LAST_PAGE, base)
        assertEquals(listOf("77"), p.marks.map { it.postId })
        assertFalse(p.hasNext)
    }

    @Test fun `post body keeps paragraphs, drops scripts and edit footer`() {
        val p = XfEngineParser.parsePost(XfFixtures.THREAD_PAGE, base, "2408634")!!
        assertEquals("Dependency Day", p.title)
        assertEquals("The ring hums.\n\nI look at my hand.", p.plain)
        assertFalse(p.html.contains("track()"))
        assertFalse(p.html.contains("Last edited"))
    }

    @Test fun `post lookup targets the anchored post, not the first one`() {
        val p = XfEngineParser.parsePost(XfFixtures.THREAD_PAGE, base, "2408639")!!
        assertEquals("A reply, not a chapter.", p.plain)
        assertNull(p.title)
    }

    @Test fun `km numbers parse`() {
        assertEquals(1300L, XfEngineParser.approxNumber("1.3k"))
        assertEquals(751L, XfEngineParser.approxNumber("751"))
        assertEquals(1_200_000L, XfEngineParser.approxNumber("1.2m"))
        assertEquals(752_000L, XfEngineParser.approxNumber("752K"))
        assertNull(XfEngineParser.approxNumber("words"))
    }
}
