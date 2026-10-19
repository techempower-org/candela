package `in`.jphe.storyvox.source.livejournal

import `in`.jphe.storyvox.source.livejournal.parser.LjRssParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LjRssParserTest {
    @Test fun `feed posts come out oldest first with unescaped bodies`() {
        val f = LjRssParser.parse(LjFixtures.RSS)!!
        assertEquals("harbor_tales", f.journal)
        assertEquals("Tales of the Harbor", f.title)
        assertEquals(listOf("1024", "2048"), f.posts.map { it.itemId })
        assertEquals("It began at the docks.", f.posts[0].plain)
        assertEquals("The tide came in.\n\nNobody moved.", f.posts[1].plain)
        assertEquals(1792396924000L, f.posts[1].publishedAt)
    }
}
