package `in`.jphe.storyvox.wear.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import `in`.jphe.storyvox.wear.theme.BrassMuted
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.WarmDarkSurface

/**
 * Brass transport controls — Skip Previous | Play/Pause | Skip Next.
 *
 * The center play/pause button is the "active" affordance and gets the full
 * brass fill; flanking skip buttons get the muted brass tint so the eye lands
 * on play/pause first. This mirrors the phone reader pattern where the active
 * sentence carries the brass underline and surrounding text is muted.
 *
 * Skip buttons map to `CMD_SKIP_BACK` / `CMD_SKIP_FWD` (30s seek) rather than
 * chapter nav — same defaults as the phone notification controls.
 *
 * When [enabled] is `false` (no phone node reachable, #1030) the buttons are
 * greyed and non-clickable — Wear's [Button] applies its disabled colours
 * automatically and TalkBack announces them as disabled — so a tap can't
 * silently no-op the way it did before.
 */
@Composable
fun TransportRow(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Skip back 30 seconds",
            onClick = onSkipBack,
            isPrimary = false,
            enabled = enabled,
        )
        TransportButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            isPrimary = true,
            enabled = enabled,
        )
        TransportButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Skip forward 30 seconds",
            onClick = onSkipForward,
            isPrimary = false,
            enabled = enabled,
        )
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    enabled: Boolean,
) {
    // Wear OS requires a ≥48dp touch target. These were 44/36dp — both under
    // the minimum, the muted skip buttons egregiously so, which made the
    // transport row hard to hit on the wrist. Primary keeps a little more
    // presence so the eye (and thumb) still land on play/pause first.
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
        // ~150ms crossfade so the play↔pause icon swap dissolves instead of
        // snapping. The skip buttons pass a constant icon, so Crossfade renders
        // them statically (it never animates its initial / unchanged state).
        Crossfade(
            targetState = icon,
            animationSpec = tween(durationMillis = 150),
            label = "transport-icon",
        ) { fadedIcon ->
            Icon(
                imageVector = fadedIcon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
