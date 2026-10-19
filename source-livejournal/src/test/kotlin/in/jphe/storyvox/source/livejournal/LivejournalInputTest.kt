package `in`.jphe.storyvox.source.livejournal

import okhttp3.OkHttpClient
import `in`.jphe.storyvox.source.livejournal.net.LivejournalApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivejournalInputTest {
    private val src = LivejournalSource(LivejournalApi(OkHttpClient()))

    @Test fun `accepts names, journal-slash-tag and urls`() {
        assertEquals(LivejournalSource.Ref("harbor_tales", null), src.parseInput("harbor_tales"))
        assertEquals(LivejournalSource.Ref("harbor_tales", "serial"), src.parseInput("harbor-tales/serial"))
        assertEquals(
            LivejournalSource.Ref("harbor_tales", "my serial"),
            src.parseInput("https://harbor-tales.livejournal.com/tag/my%20serial"),
        )
        assertNull(src.parseInput("   "))
        assertNull(src.parseInput("www.livejournal.com"))
    }
}
