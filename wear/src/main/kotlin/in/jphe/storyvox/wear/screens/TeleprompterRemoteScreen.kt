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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import `in`.jphe.storyvox.wear.R
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
 * @property currentLine the line the speaker is currently on in voice-paced
 *   mode (#1368), pushed from the phone's `VoicePacedScrollController`. Empty
 *   unless a voice-paced session is live — its non-emptiness is what switches
 *   the screen into the hands-free line display (the watch never holds the
 *   chapter text; the phone ships only these two lines).
 * @property nextLine the upcoming line, shown dimmed beneath [currentLine].
 */
data class TeleprompterRemoteUiState(
    val enabled: Boolean = false,
    val playing: Boolean = false,
    val wpm: Int = 0,
    val connected: Boolean = true,
    val currentLine: String = "",
    val nextLine: String = "",
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
            text = stringResource(R.string.wear_teleprompter),
            color = BrassPrimary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.title3,
        )
        Spacer(Modifier.height(6.dp))

        if (state.currentLine.isNotEmpty()) {
            // Issue #1368 — voice-paced line display. The phone follows the
            // speaker's voice (mic + on-device STT) and pushes the current +
            // next line; the wrist just renders them, advancing hands-free. WPM
            // is irrelevant here (the voice sets the pace), so only the lines +
            // an off toggle show. A non-empty currentLine IS the "voice mode
            // active" signal — see TeleprompterRemoteUiState.
            Text(
                text = state.currentLine,
                color = BrassPrimary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.title2,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.nextLine.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.nextLine,
                    color = BrassMuted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Turn voice follow (the whole teleprompter) off from the wrist.
            RoundIconButton(
                icon = Icons.Filled.Visibility,
                contentDescription = stringResource(R.string.wear_cd_voice_follow_on),
                onClick = onToggleEnabled,
                isPrimary = true,
                enabled = state.connected,
            )
        } else {
            // Enable / disable the mode — always tappable while connected.
            RoundIconButton(
                icon = if (state.enabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = stringResource(
                    if (state.enabled) R.string.wear_cd_teleprompter_on else R.string.wear_cd_teleprompter_off,
                ),
                onClick = onToggleEnabled,
                isPrimary = state.enabled,
                enabled = state.connected,
            )
            Spacer(Modifier.height(6.dp))

            // WPM stepper: − [ value ] +
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.wear_cd_wpm_slower),
                    onClick = { onWpmDelta(-1) },
                    isPrimary = false,
                    enabled = transportEnabled,
                )
                val wpmDescription = stringResource(R.string.wear_cd_wpm, state.wpm)
                Text(
                    text = "${state.wpm}",
                    color = if (transportEnabled) BrassPrimary else BrassMuted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.title2,
                    modifier = Modifier
                        .width(56.dp)
                        .clearAndSetSemantics { contentDescription = wpmDescription },
                )
                RoundIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.wear_cd_wpm_faster),
                    onClick = { onWpmDelta(1) },
                    isPrimary = false,
                    enabled = transportEnabled,
                )
            }
            Text(
                text = stringResource(R.string.wear_wpm_unit),
                color = BrassMuted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(6.dp))

            // Run / pause the scroll.
            RoundIconButton(
                icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (state.playing) R.string.wear_cd_scroll_pause else R.string.wear_cd_scroll_start,
                ),
                onClick = onTogglePlay,
                isPrimary = true,
                enabled = transportEnabled,
            )
        }
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
    // Wear OS ≥48dp touch target (mirrors TransportRow). Were 44/36dp.
    val size = if (isPrimary) 52.dp else 48.dp
    val iconSize = if (isPrimary) 26.dp else 22.dp
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
