package `in`.jphe.storyvox.wear.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.wear.compose.material.Icon
import `in`.jphe.storyvox.wear.R
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
 * Skip buttons are dual-action (#1404): a short press is a ±30s seek
 * (`CMD_SKIP_BACK` / `CMD_SKIP_FWD`, the phone-notification default), and a
 * long press jumps a whole chapter ([onSkipBackLong] / [onSkipForwardLong] →
 * `CMD_PREV_CH` / `CMD_NEXT_CH`). Long press fires a haptic so the chapter
 * jump is confirmed eyes-free. When a long handler is null the button is
 * tap-only (the center play/pause never has one).
 *
 * When [enabled] is `false` (no phone node reachable, #1030) the buttons are
 * greyed and non-clickable — [combinedClickable]'s `enabled` flag drops the
 * gesture and marks the node disabled for TalkBack — so a tap can't silently
 * no-op the way it did before.
 */
@Composable
fun TransportRow(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSkipBackLong: (() -> Unit)? = null,
    onSkipForwardLong: (() -> Unit)? = null,
) {
    // #a11y — externalized play-state labels (resolved here; semantics{} below
    // can't call stringResource directly).
    val playingDesc = stringResource(R.string.wear_state_playing)
    val pausedDesc = stringResource(R.string.wear_state_paused)
    Row(
        // #a11y — group the transport as one TalkBack traversal unit (each
        // button keeps its own label + action; merging descendants would have
        // flattened those away). Play state is exposed as a stateDescription on
        // the group + a polite live region, so toggling play/pause is announced
        // without the user re-focusing the button.
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                stateDescription = if (isPlaying) playingDesc else pausedDesc
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = stringResource(R.string.wear_cd_skip_back),
            onClick = onSkipBack,
            isPrimary = false,
            enabled = enabled,
            onLongClick = onSkipBackLong,
            onLongClickLabel = stringResource(R.string.wear_cd_prev_chapter),
        )
        TransportButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (isPlaying) R.string.wear_cd_pause else R.string.wear_cd_play),
            onClick = onPlayPause,
            isPrimary = true,
            enabled = enabled,
        )
        TransportButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = stringResource(R.string.wear_cd_skip_forward),
            onClick = onSkipForward,
            isPrimary = false,
            enabled = enabled,
            onLongClick = onSkipForwardLong,
            onLongClickLabel = stringResource(R.string.wear_cd_next_chapter),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    enabled: Boolean,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    // Wear OS requires a ≥48dp touch target. These were 44/36dp — both under
    // the minimum, the muted skip buttons egregiously so, which made the
    // transport row hard to hit on the wrist. Primary keeps a little more
    // presence so the eye (and thumb) still land on play/pause first.
    val size = if (isPrimary) 52.dp else 48.dp
    val iconSize = if (isPrimary) 26.dp else 22.dp
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val background = if (isPrimary) BrassPrimary else BrassMuted
    val content = if (isPrimary) WarmDarkSurface else BrassPrimary
    // Mirror the prior Wear Button's disabled treatment (#1030): a dimmed fill
    // + icon so the greyed, non-clickable state still reads as a button.
    val disabledAlpha = 0.38f

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) background else background.copy(alpha = disabledAlpha))
            // Wear Button has no long-press, so the dual-action skip buttons
            // (#1404) use combinedClickable; its `enabled` flag preserves the
            // #1030 greyed/non-clickable + TalkBack-disabled behavior.
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onClick = {
                    // Light tap so a short press registers without looking at the watch.
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
                onLongClick = onLongClick?.let { action ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        action()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
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
                tint = if (enabled) content else content.copy(alpha = disabledAlpha),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
