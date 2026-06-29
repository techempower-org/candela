package `in`.jphe.storyvox.wear.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import `in`.jphe.storyvox.wear.theme.BrassMuted
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.WarmDarkSurface

/**
 * Issue #1308 — wrist teleprompter remote. Snapshot of the teleprompter state
 * the watch shows + drives.
 *
 * @property enabled teleprompter mode on/off (phone `TeleprompterController.enabled`).
 * @property playing scroll running vs paused (`TeleprompterController.playing`).
 * @property wpm current words-per-minute (`TeleprompterController.wpm`).
 * @property connected a phone node is reachable; when false, all controls grey
 *   out (mirrors the transport controls' disconnected handling).
 */
data class TeleprompterRemoteUiState(
    val enabled: Boolean = false,
    val playing: Boolean = false,
    val wpm: Int = 0,
    val connected: Boolean = true,
)

/**
 * Issue #1308 — dedicated Wear teleprompter-remote surface (reached from
 * [NowPlayingScreen]). Stateless: the caller supplies the synced state and the
 * send callbacks (wired to [in.jphe.storyvox.wear.playback.WearPlaybackBridge]),
 * so this composable is pure presentation and the integration wiring stays out
 * of it.
 *
 * Controls: an **enable** toggle, **play/pause** of the scroll, and a
 * **`− wpm +`** stepper. Play/pause + the stepper are only active once the mode
 * is enabled; everything greys out when the phone is unreachable. Brass-on-warm-dark
 * per the Wear Library Nocturne theme, mirroring [TransportRow]'s button idiom.
 */
@Composable
fun TeleprompterRemoteScreen(
    state: TeleprompterRemoteUiState,
    onToggleEnabled: () -> Unit,
    onTogglePlay: () -> Unit,
    onWpmDelta: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transportEnabled = state.connected && state.enabled
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Teleprompter",
            color = BrassPrimary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.title3,
        )
        Spacer(Modifier.height(8.dp))

        // Enable / disable the mode — always tappable while connected.
        RoundIconButton(
            icon = if (state.enabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = if (state.enabled) "Teleprompter on, tap to turn off" else "Teleprompter off, tap to turn on",
            onClick = onToggleEnabled,
            isPrimary = state.enabled,
            enabled = state.connected,
        )
        Spacer(Modifier.height(10.dp))

        // WPM stepper: − [ value ] +
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                icon = Icons.Filled.Remove,
                contentDescription = "Slower",
                onClick = { onWpmDelta(-1) },
                isPrimary = false,
                enabled = transportEnabled,
            )
            Text(
                text = "${state.wpm}",
                color = if (transportEnabled) BrassPrimary else BrassMuted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.title2,
                modifier = Modifier.width(56.dp),
            )
            RoundIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "Faster",
                onClick = { onWpmDelta(1) },
                isPrimary = false,
                enabled = transportEnabled,
            )
        }
        Text(
            text = "wpm",
            color = BrassMuted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption2,
        )
        Spacer(Modifier.height(10.dp))

        // Run / pause the scroll.
        RoundIconButton(
            icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (state.playing) "Pause scroll" else "Start scroll",
            onClick = onTogglePlay,
            isPrimary = true,
            enabled = transportEnabled,
        )
    }
}

/**
 * Brass round icon button — mirrors `TransportRow`'s private button so the
 * teleprompter controls match the transport vocabulary (primary = full brass
 * fill, secondary = muted tint, disabled greys via Wear's [Button]).
 */
@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    enabled: Boolean,
) {
    val size = if (isPrimary) 44.dp else 36.dp
    val iconSize = if (isPrimary) 24.dp else 20.dp
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        colors = if (isPrimary) {
            ButtonDefaults.primaryButtonColors(
                backgroundColor = BrassPrimary,
                contentColor = WarmDarkSurface,
            )
        } else {
            ButtonDefaults.secondaryButtonColors(
                backgroundColor = BrassMuted,
                contentColor = BrassPrimary,
            )
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}
