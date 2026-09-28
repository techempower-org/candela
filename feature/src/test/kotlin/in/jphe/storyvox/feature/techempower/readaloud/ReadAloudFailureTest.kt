package `in`.jphe.storyvox.feature.techempower.readaloud

import `in`.jphe.storyvox.feature.api.UiSpeakOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #1776 — which read-aloud outcome shows which message. */
class ReadAloudFailureTest {

    @Test
    fun `started shows no failure`() {
        assertNull(ReadAloudSession.failureFor(UiSpeakOutcome.Started))
    }

    @Test
    fun `cancelled or superseded shows no failure`() {
        assertNull(ReadAloudSession.failureFor(UiSpeakOutcome.Cancelled))
    }

    @Test
    fun `no voice points the user at Voices`() {
        assertEquals(ReadAloudFailure.NoVoice, ReadAloudSession.failureFor(UiSpeakOutcome.NoVoice))
    }

    @Test
    fun `engine that never came up asks for a retry`() {
        assertEquals(ReadAloudFailure.EngineUnavailable, ReadAloudSession.failureFor(UiSpeakOutcome.Unavailable))
    }
}
