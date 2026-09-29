package `in`.jphe.storyvox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TechEmpowerLinksTest {

    @Test
    fun `discord https and deep-link forms carry the same invite`() {
        assertEquals(
            TechEmpowerLinks.DISCORD_INVITE_URL.substringAfterLast('/'),
            TechEmpowerLinks.DISCORD_INVITE_DEEPLINK.substringAfterLast('/'),
        )
    }

    @Test
    fun `discord invite lands in welcome, not the board channel`() {
        // j3SVttxw7k targets #board-of-directors (a private channel whose
        // name the public invite preview leaks). 7wDhAG3vYS targets #welcome.
        assertNotEquals("j3SVttxw7k", TechEmpowerLinks.DISCORD_INVITE_URL.substringAfterLast('/'))
        assertEquals("7wDhAG3vYS", TechEmpowerLinks.DISCORD_INVITE_URL.substringAfterLast('/'))
    }
}
