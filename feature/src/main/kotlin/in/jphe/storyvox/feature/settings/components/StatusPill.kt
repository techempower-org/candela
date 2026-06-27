package `in`.jphe.storyvox.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * A small status pill: a colored dot + a single line of text. Sits at
 * the top of a [SettingsGroupCard] as the first row so the user sees
 * connectable-backend state without scrolling.
 *
 * Three tones, each mapping to one of the three brand colors so a wall
 * of pills reads coherently:
 *
 *  - [StatusTone.Neutral]   — `onSurfaceVariant` (warm gray). "Not configured",
 *                              "Tap to verify".
 *  - [StatusTone.Connected] — `primary` (brass). "Connected · daemon 1.2".
 *  - [StatusTone.Error]     — `error`. "Off home network", "Key rejected".
 *
 * Render this *inside* the group card as the leading row. The card
 * provides the warm container; the pill provides only its dot, copy,
 * and a thin top/bottom of padding that matches `SettingsRow`.
 */
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val color: Color = when (tone) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.Connected -> MaterialTheme.colorScheme.primary
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            // #1160: pill text flips ("Syncing…" → "Connected" / "Key rejected")
            // without a tap, so TalkBack must announce it. Errors are assertive
            // (interrupt) since they're actionable; healthy/neutral states are polite.
            .semantics {
                liveRegion = if (tone == StatusTone.Error) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * Severity / connection-state for a [StatusPill].
 */
enum class StatusTone {
    /** Default / not-yet-acted-on state. Warm gray. */
    Neutral,
    /** Successful connection or healthy state. Brass primary. */
    Connected,
    /** Failed connection or error state. Theme error color. */
    Error,
}

@Preview(name = "StatusPill — three tones (dark)", widthDp = 360)
@Composable
private fun PreviewStatusPillDark() = LibraryNocturneTheme(darkTheme = true) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusPill(text = "Not configured", tone = StatusTone.Neutral)
        StatusPill(text = "Connected · 92 voices available", tone = StatusTone.Connected)
        StatusPill(text = "Key rejected · re-paste from Azure portal", tone = StatusTone.Error)
    }
}

@Preview(name = "StatusPill — three tones (light)", widthDp = 360)
@Composable
private fun PreviewStatusPillLight() = LibraryNocturneTheme(darkTheme = false) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusPill(text = "Not configured", tone = StatusTone.Neutral)
        StatusPill(text = "Connected · 92 voices available", tone = StatusTone.Connected)
        StatusPill(text = "Key rejected · re-paste from Azure portal", tone = StatusTone.Error)
    }
}
