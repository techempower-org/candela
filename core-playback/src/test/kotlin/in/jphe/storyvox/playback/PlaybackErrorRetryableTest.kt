package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wear companion — pins the shared [PlaybackError.isRetryable] /
 * [PlaybackError.watchSummary] contract that both the phone reader banner
 * (via PlaybackController) and the Wear NowPlaying error chip read from, so
 * the two surfaces can never disagree on which errors offer a Retry.
 */
class PlaybackErrorRetryableTest {

    @Test
    fun `auth failure and missing engine are terminal`() {
        // Re-paste a key / install an engine — nothing a Retry can fix.
        assertFalse(PlaybackError.AzureAuthFailed.isRetryable())
        assertFalse(PlaybackError.EngineUnavailable.isRetryable())
    }

    @Test
    fun `every transient error is retryable`() {
        assertTrue(PlaybackError.ChapterFetchFailed("boom").isRetryable())
        assertTrue(PlaybackError.TtsSpeakFailed("u", 7).isRetryable())
        assertTrue(PlaybackError.AzureThrottled("slow down").isRetryable())
        assertTrue(PlaybackError.AzureNetworkUnavailable("offline").isRetryable())
        assertTrue(PlaybackError.AzureServerError(503, "azure down").isRetryable())
    }

    @Test
    fun `watchSummary reuses the message-bearing copy verbatim`() {
        // The #1262/#1311 chapter errors carry their own user-facing text;
        // the watch shows it as-is rather than inventing a second wording.
        assertEquals(
            "This chapter couldn't be read aloud — no audio was produced.",
            PlaybackError.ChapterFetchFailed(
                "This chapter couldn't be read aloud — no audio was produced.",
            ).watchSummary(),
        )
        assertEquals("quota hit", PlaybackError.AzureThrottled("quota hit").watchSummary())
        assertEquals("no wifi", PlaybackError.AzureNetworkUnavailable("no wifi").watchSummary())
        assertEquals("500", PlaybackError.AzureServerError(500, "500").watchSummary())
    }

    @Test
    fun `watchSummary gives a non-blank fixed label for payload-less errors`() {
        assertTrue(PlaybackError.EngineUnavailable.watchSummary().isNotBlank())
        assertTrue(PlaybackError.AzureAuthFailed.watchSummary().isNotBlank())
        assertTrue(PlaybackError.TtsSpeakFailed("u", 7).watchSummary().isNotBlank())
    }
}
