package `in`.jphe.storyvox.feature.reader

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.playback.StoryvoxPlaybackService
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.a11y.accessibleSize
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Why the Media3 playback notification can't be seen, if it can't.
 *
 * Reported from the field: "the playback button is not showing on top of my
 * phone screen." On Android 13+ POST_NOTIFICATIONS is requested once at launch
 * (MainActivity.maybeRequestNotificationPermission); a "Don't allow" there, or
 * a user switching off just the `playback` channel, silently removes the
 * shade + lock-screen controls with no in-app trace.
 */
enum class PlaybackNotificationBlock {
    /** Notifications reach the user — no banner. */
    None,

    /** All app notifications are off (permission denied / app toggle off). */
    App,

    /** App notifications are on, but the playback channel is set to "none". */
    Channel,
}

/**
 * Pure decision for [PlaybackNotificationsBanner]. JVM-tested in
 * `PlaybackNotificationBlockTest`.
 *
 * @param appNotificationsEnabled `NotificationManagerCompat.areNotificationsEnabled()`.
 * @param playbackChannelImportance the playback channel's importance, or `null`
 *   when the channel doesn't exist yet (the service creates it on first start —
 *   nothing to be blocked, so no banner).
 */
fun playbackNotificationBlock(
    appNotificationsEnabled: Boolean,
    playbackChannelImportance: Int?,
): PlaybackNotificationBlock = when {
    !appNotificationsEnabled -> PlaybackNotificationBlock.App
    playbackChannelImportance == NotificationManager.IMPORTANCE_NONE ->
        PlaybackNotificationBlock.Channel
    else -> PlaybackNotificationBlock.None
}

/** Reads the live system state and folds it through [playbackNotificationBlock]. */
internal fun readPlaybackNotificationBlock(context: Context): PlaybackNotificationBlock {
    val nm = NotificationManagerCompat.from(context)
    return playbackNotificationBlock(
        appNotificationsEnabled = nm.areNotificationsEnabled(),
        playbackChannelImportance = nm
            .getNotificationChannel(StoryvoxPlaybackService.CHANNEL_PLAYBACK)
            ?.importance,
    )
}

/**
 * "Dismiss for this session": process-scoped, so it survives navigating away
 * from Playing and back, and resets on the next cold start — the problem is
 * still real, we just stop nagging for now.
 */
private object PlaybackNotificationsBannerSession {
    var dismissed by mutableStateOf(false)
}

/**
 * Self-contained banner for the Playing screen: reads the notification state,
 * re-reads it on every ON_RESUME (so it disappears the moment the user comes
 * back from system Settings with notifications on), and deep-links to the
 * right Settings page. Renders nothing when notifications are fine.
 */
@Composable
fun PlaybackNotificationsBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var block by remember { mutableStateOf(readPlaybackNotificationBlock(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        block = readPlaybackNotificationBlock(context)
    }
    if (block == PlaybackNotificationBlock.None || PlaybackNotificationsBannerSession.dismissed) return

    PlaybackNotificationsBannerContent(
        onTurnOn = { openNotificationSettings(context, block) },
        onDismiss = { PlaybackNotificationsBannerSession.dismissed = true },
        modifier = modifier,
    )
}

private fun openNotificationSettings(context: Context, block: PlaybackNotificationBlock) {
    val intent = when (block) {
        PlaybackNotificationBlock.Channel ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, StoryvoxPlaybackService.CHANNEL_PLAYBACK)
        else ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    // Some OEM Settings apps don't export these screens; same guard as
    // NotificationsSettingsScreen.
    runCatching { context.startActivity(intent) }
}

@Composable
private fun PlaybackNotificationsBannerContent(
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    // Same Card + surfaceVariant idiom as OfflineBanner — an inline status
    // strip, not an error. Polite live region so TalkBack announces it
    // without interrupting whatever is being read aloud.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.md, top = spacing.sm, bottom = spacing.sm, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.NotificationsOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.reader_notifications_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.reader_notifications_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BrassButton(
                label = stringResource(R.string.reader_notifications_off_turn_on),
                onClick = onTurnOn,
                variant = BrassButtonVariant.Text,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.accessibleSize(
                    enlargedFlag = LocalAccessibleTouchTargets.current,
                    base = 48.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.reader_notifications_off_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// region Previews

@Preview(name = "Notifications off (dark)", widthDp = 360)
@Composable
private fun PreviewNotificationsOffDark() = LibraryNocturneTheme(darkTheme = true) {
    PlaybackNotificationsBannerContent(onTurnOn = {}, onDismiss = {})
}

@Preview(name = "Notifications off (light)", widthDp = 360)
@Composable
private fun PreviewNotificationsOffLight() = LibraryNocturneTheme(darkTheme = false) {
    PlaybackNotificationsBannerContent(onTurnOn = {}, onDismiss = {})
}

// endregion
