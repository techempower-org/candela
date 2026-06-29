package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1308 — the teleprompter control state, hoisted out of `ReaderView` into
 * this shared `@Singleton` so the phone reader and the Wear remote (PR2) drive
 * one source of truth. These pin the holder's defaults + setter→StateFlow
 * contract (the reader collects these flows and calls the setters).
 */
class TeleprompterControllerTest {

    @Test
    fun `defaults are off, off, and the 130 wpm pre-seed`() {
        val controller = TeleprompterController()
        assertFalse("teleprompter starts disabled", controller.enabled.value)
        assertFalse("teleprompter starts not playing", controller.playing.value)
        assertEquals("pre-seed pace mirrors TELEPROMPTER_DEFAULT_WPM", 130, controller.wpm.value)
    }

    @Test
    fun `setters update their state flows`() {
        val controller = TeleprompterController()

        controller.setEnabled(true)
        assertTrue(controller.enabled.value)

        controller.setPlaying(true)
        assertTrue(controller.playing.value)

        controller.setWpm(220)
        assertEquals(220, controller.wpm.value)

        // The reader resets the transient controls on dispose (#1308); the
        // holder just stores whatever it's given.
        controller.setEnabled(false)
        controller.setPlaying(false)
        assertFalse(controller.enabled.value)
        assertFalse(controller.playing.value)
        assertEquals("wpm is independent of enabled/playing", 220, controller.wpm.value)
    }
}
