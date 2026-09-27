package `in`.jphe.storyvox.feature.reader

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the show/hide decision behind the Playing screen's
 * "Playback controls are hidden" banner, per the device matrix in
 * [PlaybackNotificationBlock]'s kdoc (Galaxy Tab A7 Lite, Android 14):
 *  - API 33+: app notifications off does NOT hide the media transport
 *    (media-session exemption) — only the `playback` channel at NONE does.
 *  - API 26–32: app notifications off hides everything, media included.
 *  - A missing channel means the service hasn't run yet — not blocked.
 */
class PlaybackNotificationBlockTest {

    private val none = NotificationManager.IMPORTANCE_NONE
    private val low = NotificationManager.IMPORTANCE_LOW

    // ---- API 33+ (media exemption) ----

    @Test
    fun `api 34 app notifications off with a healthy channel hides the banner`() {
        // Device case B / D — controls stayed visible with the permission revoked.
        assertEquals(
            PlaybackNotificationBlock.None,
            playbackNotificationBlock(sdkInt = 34, appNotificationsEnabled = false, playbackChannelImportance = low),
        )
    }

    @Test
    fun `api 33 app notifications off with no channel yet hides the banner`() {
        assertEquals(
            PlaybackNotificationBlock.None,
            playbackNotificationBlock(sdkInt = 33, appNotificationsEnabled = false, playbackChannelImportance = null),
        )
    }

    @Test
    fun `api 34 blocked playback channel shows the channel banner`() {
        // Device case C — reproduces the field report.
        assertEquals(
            PlaybackNotificationBlock.Channel,
            playbackNotificationBlock(sdkInt = 34, appNotificationsEnabled = true, playbackChannelImportance = none),
        )
    }

    @Test
    fun `api 34 app off and channel blocked still shows the channel banner`() {
        assertEquals(
            PlaybackNotificationBlock.Channel,
            playbackNotificationBlock(sdkInt = 34, appNotificationsEnabled = false, playbackChannelImportance = none),
        )
    }

    @Test
    fun `api 34 enabled with a low-importance channel hides the banner`() {
        // Device case A — the service creates the channel at LOW: silent but visible.
        assertEquals(
            PlaybackNotificationBlock.None,
            playbackNotificationBlock(sdkInt = 34, appNotificationsEnabled = true, playbackChannelImportance = low),
        )
    }

    // ---- API 26–32 (no media exemption) ----

    @Test
    fun `api 32 app notifications off shows the app banner`() {
        assertEquals(
            PlaybackNotificationBlock.App,
            playbackNotificationBlock(sdkInt = 32, appNotificationsEnabled = false, playbackChannelImportance = low),
        )
    }

    @Test
    fun `api 26 app notifications off wins over a blocked channel`() {
        // The channel page is unreachable while the app toggle is off; fix the app first.
        assertEquals(
            PlaybackNotificationBlock.App,
            playbackNotificationBlock(sdkInt = 26, appNotificationsEnabled = false, playbackChannelImportance = none),
        )
    }

    @Test
    fun `api 30 blocked playback channel shows the channel banner`() {
        assertEquals(
            PlaybackNotificationBlock.Channel,
            playbackNotificationBlock(sdkInt = 30, appNotificationsEnabled = true, playbackChannelImportance = none),
        )
    }

    @Test
    fun `api 30 enabled with a missing channel hides the banner`() {
        assertEquals(
            PlaybackNotificationBlock.None,
            playbackNotificationBlock(sdkInt = 30, appNotificationsEnabled = true, playbackChannelImportance = null),
        )
    }
}
