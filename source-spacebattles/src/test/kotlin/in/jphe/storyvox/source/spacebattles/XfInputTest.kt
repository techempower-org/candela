package `in`.jphe.storyvox.source.spacebattles

import `in`.jphe.storyvox.source.spacebattles.net.SpacebattlesApi
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Search-input parsing: thread URLs, bare ids, and cross-site rejection. */
class XfInputTest {
    private val source = SpacebattlesSource(SpacebattlesApi(OkHttpClient()))

    @Test fun `accepts a full thread URL on the site's own host`() {
        assertEquals(
            "8961",
            source.parseThreadInput("https://forums.spacebattles.com/threads/with-this-ring.8961/page-4#post-1"),
        )
    }

    @Test fun `accepts a bare id and a threads path`() {
        assertEquals("8961", source.parseThreadInput("8961"))
        assertEquals("8961", source.parseThreadInput("/threads/with-this-ring.8961/"))
    }

    @Test fun `rejects another site's URL and plain prose`() {
        assertNull(source.parseThreadInput("https://forums.sufficientvelocity.com/threads/story.123/"))
        assertNull(source.parseThreadInput("a story about rings"))
        assertNull(source.parseThreadInput(""))
    }
}
