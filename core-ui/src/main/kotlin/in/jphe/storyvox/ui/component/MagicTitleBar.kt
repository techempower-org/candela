package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #830 — the shared title bar for every primary-nav surface
 * (Library / Browse / Follows / Voice Library / Settings Hub).
 *
 * Before this composable existed each tab built its own bar inline,
 * which drifted: three used [CenterAlignedTopAppBar] with `titleMedium`,
 * two used a left-aligned [androidx.compose.material3.TopAppBar] with
 * `titleLarge`, and Follows overrode the container color to `surface`.
 * The visible result was five subtly different headers across one app.
 *
 * [MagicTitleBar] picks the most polished pattern (center-aligned,
 * `titleMedium`, default container) and adds a small brass-tinted
 * sparkle glyph next to the title. The glyph is the same
 * [Icons.Outlined.AutoAwesome] used by the Settings hub heading, so it
 * reads as one design family. Slots for [navigationIcon] and [actions]
 * mirror the underlying [CenterAlignedTopAppBar] API.
 *
 * Issue #1195 — pass a [scrollBehavior] (e.g.
 * `TopAppBarDefaults.enterAlwaysScrollBehavior()`) to let the bar slide
 * away as the screen's content scrolls down and return on scroll up,
 * reclaiming vertical space on small phones. The host must also wire
 * `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` onto
 * its [androidx.compose.material3.Scaffold]. Default null keeps the bar
 * pinned (the behavior for every call site that doesn't opt in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val spacing = LocalSpacing.current
    CenterAlignedTopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    // #1136 — mark the screen title as a heading so TalkBack's
                    // heading-navigation gesture can jump to it. The sparkle
                    // Icon beside it is decorative (contentDescription = null),
                    // so the Text is the right node to carry the heading role.
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        navigationIcon = navigationIcon,
        actions = { actions() },
    )
}

/**
 * Structural canary (#1136) — `MagicTitleBar`'s title `Text` must carry
 * `Modifier.semantics { heading() }` so TalkBack's heading-navigation
 * gesture can jump to the title of every primary-nav surface (Library /
 * Browse / Follows / Voice Library / Settings Hub). Compose semantics
 * can't be asserted from the JVM unit-test source set (no Robolectric —
 * see `BottomTabBarSemanticsTest`), so this marker is pinned `true` by
 * `MagicTitleBarSemanticsTest`. Flip to false only after proving an
 * equivalent heading announcement on-device with TalkBack.
 */
internal const val magicTitleBarMarksTitleAsHeading: Boolean = true

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "MagicTitleBar — dark", widthDp = 360)
@Composable
private fun PreviewMagicTitleBarDark() = LibraryNocturneTheme(darkTheme = true) {
    MagicTitleBar(title = "Library")
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "MagicTitleBar — light", widthDp = 360)
@Composable
private fun PreviewMagicTitleBarLight() = LibraryNocturneTheme(darkTheme = false) {
    MagicTitleBar(title = "Voice library")
}
