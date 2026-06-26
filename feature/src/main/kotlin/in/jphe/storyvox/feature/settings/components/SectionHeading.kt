package `in`.jphe.storyvox.feature.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Section heading — title in brass + optional icon + optional one-line
 * descriptor in warm gray underneath. Sits *outside* the
 * `SettingsGroupCard` so it reads as a chapter heading.
 *
 * This supersedes the older `SettingsSectionHeader` for new call sites
 * that want the descriptor text per the redesign spec ("Section headers
 * should have icons + a short descriptor — not just a bare h3."). The
 * older composable still exists in `SettingsComposables.kt` for cases
 * where a descriptor isn't called for; new sections should prefer this
 * one for consistency.
 */
@Composable
fun SectionHeading(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    descriptor: String? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(start = spacing.xs, top = spacing.xs, bottom = spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                // #1136 — the label (not the descriptor) is the heading, so
                // TalkBack heading-navigation lands on the section name.
                modifier = Modifier.semantics { heading() },
            )
        }
        if (!descriptor.isNullOrBlank()) {
            Text(
                text = descriptor,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Structural canary (#1136) — `SectionHeading`'s label `Text` must carry
 * `heading()` semantics. Pinned by `SettingsHeadingSemanticsTest` since
 * Compose semantics aren't assertable from JVM unit tests (no Robolectric).
 */
internal const val sectionHeadingMarksLabelAsHeading: Boolean = true

@Preview(name = "SectionHeading — with descriptor (dark)", widthDp = 360)
@Composable
private fun PreviewSectionHeadingDark() = LibraryNocturneTheme(darkTheme = true) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            label = "Voice & Playback",
            icon = Icons.Outlined.RecordVoiceOver,
            descriptor = "How storyvox sounds — voice, speed, cadence.",
        )
        SectionHeading(label = "Reading (no descriptor)")
    }
}

@Preview(name = "SectionHeading — with descriptor (light)", widthDp = 360)
@Composable
private fun PreviewSectionHeadingLight() = LibraryNocturneTheme(darkTheme = false) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(
            label = "Voice & Playback",
            icon = Icons.Outlined.RecordVoiceOver,
            descriptor = "How storyvox sounds — voice, speed, cadence.",
        )
    }
}
