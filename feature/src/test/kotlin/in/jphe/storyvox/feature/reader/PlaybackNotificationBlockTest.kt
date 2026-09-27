package `in`.jphe.storyvox.feature.reader

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the show/hide decision behind the Playing screen's
 * "Playback controls are hidden" banner. App-level denial wins over the
 * channel check (the channel page is unreachable while the app toggle is
 * off); a missing channel means the service hasn't run yet — not blocked.
 */
class PlaybackNotificationBlockTest {

    @Test
    fun `app notifications off shows the app banner`() {
        assertEquals(
            PlaybackNotificationBlock.App,
            playbackNotificationBlock(appNotificationsEnabled = false, playbackChannelImportance = null),
        )
    }

    @Test
    fun `app notifications off wins over a healthy channel`() {
        assertEquals(
            PlaybackNotificationBlock.App,
            playbackNotificationBlock(
                appNotificationsEnabled = false,
                playbackChannelImportance = NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Test
    fun `app notifications off wins over a blocked channel`() {
        assertEquals(
            PlaybackNotificationBlock.App,
            playbackNotificationBlock(
                appNotificationsEnabled = false,
                playbackChannelImportance = NotificationManager.IMPORTANCE_NONE,
            ),
        )
    }

    @Test
    fun `blocked playback channel shows the channel banner`() {
        assertEquals(
            PlaybackNotificationBlock.Channel,
            playbackNotificationBlock(
                appNotificationsEnabled = true,
                playbackChannelImportance = NotificationManager.IMPORTANCE_NONE,
            ),
        )
    }

    @Test
    fun `enabled with a low-importance channel hides the banner`() {
        // The service creates the channel at IMPORTANCE_LOW — silent but visible.
        assertEquals(
            PlaybackNotificationBlock.None,
            playbackNotificationBlock(
                appNotificationsEnabled = true,
                playbackChannelImportance = NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Test
    fun `enabled with a missing channel hides the banner`() {
        assertEquals(
            PlaybackNotificationBlock.None,
            playbackNotificationBlock(appNotificationsEnabled = true, playbackChannelImportance = null),
        )
    }
}
