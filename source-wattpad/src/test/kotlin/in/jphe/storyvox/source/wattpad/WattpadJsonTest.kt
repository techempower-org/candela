package `in`.jphe.storyvox.source.wattpad

import `in`.jphe.storyvox.source.wattpad.net.WattpadJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WattpadJsonTest {
    @Test fun `list parses and keeps mature flag for filtering`() {
        val l = WattpadJson.parseList(WattpadFixtures.LIST)
        assertEquals(2, l.stories.size)
        assertEquals("Dragon__Wolf", l.stories[0].author)
        assertTrue(l.stories[1].mature)
        assertTrue(l.hasNext)
    }

    @Test fun `story parts skip drafts and accept numeric ids`() {
        val (s, parts) = WattpadJson.parseStory(WattpadFixtures.STORY)!!
        assertTrue(s.completed)
        assertEquals(listOf("80845154", "80847228"), parts.map { it.id })
        assertEquals(1422007759000L, parts[0].createdAt)
    }
}
