package `in`.jphe.storyvox.wear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import `in`.jphe.storyvox.wear.theme.BrassMuted
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.BrassTint
import `in`.jphe.storyvox.wear.theme.ParchmentOnMuted
import `in`.jphe.storyvox.wear.theme.WarmDarkContainer
import `in`.jphe.storyvox.wear.theme.WarmDarkContainerHigh
import `in`.jphe.storyvox.wear.theme.WearLibraryNocturneTheme

/**
 * Wrist sleep-timer surface (reached from [NowPlayingScreen]). Mirrors
 * [TeleprompterRemoteScreen]: stateless brass-on-warm-dark presentation; the
 * caller supplies the synced [remainingMs] (from
 * `PlaybackState.sleepTimerRemainingMs`) and the send callbacks (wired to
 * [in.jphe.storyvox.wear.playback.WearPlaybackBridge]).
 *
 * Options: 15 / 30 / 45 minutes, End of chapter, and Cancel. Cancel is always
 * offered (the phone's cancel is idempotent) so an end-of-chapter timer — which
 * has no countdown to surface — is still dismissable from the wrist.
 *
 * @property remainingMs ms until pause for a *duration* timer, or null (no timer,
 *   or an end-of-chapter timer that hasn't started its fade — no countdown).
 * @property connected a phone node is reachable; when false the options grey out.
 */
data class SleepTimerUiState(
    val remainingMs: Long? = null,
    val connected: Boolean = true,
)

@Composable
fun SleepTimerScreen(
    state: SleepTimerUiState,
    onPickDuration: (minutes: Int) -> Unit,
    onPickEndOfChapter: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Sleep timer",
            color = BrassPrimary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.title3,
        )
        // Active duration timer → show the live countdown synced from the phone.
        if (state.remainingMs != null) {
            Text(
                text = formatSleepRemaining(state.remainingMs),
                color = BrassTint,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.title2,
            )
            Text(
                text = "until pause",
                color = ParchmentOnMuted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
            )
        }
        Spacer(Modifier.height(2.dp))

        SleepOption("15 minutes", enabled = state.connected) { onPickDuration(15) }
        SleepOption("30 minutes", enabled = state.connected) { onPickDuration(30) }
        SleepOption("45 minutes", enabled = state.connected) { onPickDuration(45) }
        SleepOption("End of chapter", enabled = state.connected) { onPickEndOfChapter() }
        SleepOption("Cancel", enabled = state.connected, destructive = true) { onCancel() }
    }
}

/** Format ms-until-pause as `m:ss` (sleep timers cap at 45 min, so no hours). */
internal fun formatSleepRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * A full-width, rounded, tappable option row — hand-rolled (Box + clickable)
 * rather than a Wear `Chip` to stay on the exact component set
 * [TeleprompterRemoteScreen] uses. Brass-on-warm-dark; greys out when the phone
 * is unreachable; [destructive] (Cancel) tints with the muted brass fill.
 */
@Composable
private fun SleepOption(
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> WarmDarkContainer
        destructive -> BrassMuted
        else -> WarmDarkContainerHigh
    }
    val foreground = when {
        !enabled -> BrassMuted
        destructive -> BrassTint
        else -> BrassPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.button,
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Sleep · idle")
@Composable
private fun PreviewSleepIdle() = WearLibraryNocturneTheme {
    SleepTimerScreen(
        state = SleepTimerUiState(remainingMs = null, connected = true),
        onPickDuration = {}, onPickEndOfChapter = {}, onCancel = {},
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Sleep · running")
@Composable
private fun PreviewSleepRunning() = WearLibraryNocturneTheme {
    SleepTimerScreen(
        state = SleepTimerUiState(remainingMs = 14L * 60_000 + 32_000, connected = true),
        onPickDuration = {}, onPickEndOfChapter = {}, onCancel = {},
    )
}
