package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion

/**
 * Library Nocturne progress bar. Brass fill on a thin rail.
 *
 * - Determinate: pass `progress` in `0f..1f`. Fill animates smoothly to the
 *   target. Standalone progress bar — caller is responsible for showing
 *   bytes/percent labels above or beside it. Use this for downloads, sync
 *   progress, chapter-fraction in library cards.
 * - Indeterminate: pass `progress = null`. A brass shimmer sweeps across
 *   the rail at a slow, deliberate cadence (1800ms loop) — feels alive,
 *   not anxious. The Library Nocturne motion vocabulary is "warm and
 *   slow"; M3's `LinearProgressIndicator` indeterminate at ~750ms reads
 *   as urgent in this context.
 * - Reduced motion: indeterminate collapses to a static brass-tinted rail
 *   at 30% fill. Determinate skips the entry animation and snaps to the
 *   current value. The user still sees "something is happening" via the
 *   surrounding text label, just without movement.
 *
 * Distinct from [BrassProgressTrack], which is the **interactive** seek
 * control on the audiobook player. This component is passive — observers
 * can't drag it.
 *
 * Accessibility (#1156): carries [progressSemantics] so TalkBack has a
 * non-visual equivalent for the brass-on-rail drawing — the determinate
 * fraction is announced as a percentage; the indeterminate forms mark the
 * node busy. Without it the pure `drawBehind` rail is invisible to a
 * screen reader (WCAG 1.1.1 / 4.1.3). Status text at the call site should
 * still carry its own `liveRegion` for the Done/Failed transitions.
 *
 * @param progress 0..1 fraction. `null` for indeterminate.
 * @param modifier sizing modifier; height defaults to 4.dp via the rail
 *   parameter.
 * @param railHeight thickness of the rail; 4.dp is the default and matches
 *   M3's `LinearProgressIndicator` so existing layouts don't reflow.
 * @param trackColor the rail substrate. Defaults to `outlineVariant` so
 *   the bar reads against any card.
 * @param fillColor the brass fill. Defaults to `primary`.
 */
@Composable
fun BrassProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    railHeight: Dp = 4.dp,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
    fillColor: Color = MaterialTheme.colorScheme.primary,
) {
    val reducedMotion = LocalReducedMotion.current
    val shape = MaterialTheme.shapes.small

    if (progress != null) {
        val clamped = progress.coerceIn(0f, 1f)
        // Smooth-animate the fill on changes — feels like the bar is
        // chasing the current value, not snapping. 360ms matches the
        // standard `swipeDurationMs` so progress updates feel of-a-piece
        // with reader<->audiobook swipes happening on the same screen.
        val animated by animateFloatAsState(
            targetValue = clamped,
            animationSpec = tween(
                durationMillis = if (reducedMotion) 0 else 360,
                easing = LinearEasing,
            ),
            label = "brass-progress-fill",
        )
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(railHeight)
                // a11y (#1156) — the bar is a pure drawBehind Box, so without
                // this it has no non-visual equivalent (WCAG 1.1.1) and the
                // percentage is silent everywhere the bar is used. Report the
                // determinate fraction so TalkBack announces "N percent".
                .progressSemantics(clamped)
                .clip(shape)
                .drawBehind {
                    drawRect(trackColor, size = size)
                    drawRect(
                        fillColor,
                        size = Size(size.width * animated, size.height),
                    )
                },
        )
    } else if (reducedMotion) {
        // Static brass-tinted rail. The user reads "in flight" from the
        // surrounding label, not from motion.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(railHeight)
                // a11y (#1156) — indeterminate equivalent: marks the node as
                // a busy progress bar so TalkBack announces "in progress".
                .progressSemantics()
                .clip(shape)
                .drawBehind {
                    drawRect(trackColor, size = size)
                    drawRect(
                        fillColor.copy(alpha = 0.4f),
                        size = Size(size.width * 0.30f, size.height),
                    )
                },
        )
    } else {
        // Indeterminate: a brass comet slides along the rail with a soft
        // gradient halo. The comet width is 35% of the rail and its
        // center travels from -0.175 to 1.175 (so the leading edge slides
        // off-screen rather than truncating awkwardly at the edges).
        val transition = rememberInfiniteTransition(label = "brass-progress-sweep")
        val phase by transition.animateFloat(
            initialValue = -0.175f,
            targetValue = 1.175f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(railHeight)
                // a11y (#1156) — indeterminate equivalent for the animated
                // comet: TalkBack announces "in progress" rather than silence.
                .progressSemantics()
                .clip(shape)
                .drawBehind {
                    drawRect(trackColor, size = size)
                    val cometWidthFraction = 0.35f
                    val cometWidth = size.width * cometWidthFraction
                    val cometCenter = size.width * phase
                    val cometStart = cometCenter - cometWidth / 2f
                    val brush = Brush.linearGradient(
                        colors = listOf(
                            fillColor.copy(alpha = 0.0f),
                            fillColor.copy(alpha = 0.85f),
                            fillColor,
                            fillColor.copy(alpha = 0.85f),
                            fillColor.copy(alpha = 0.0f),
                        ),
                        start = Offset(cometStart, 0f),
                        end = Offset(cometStart + cometWidth, 0f),
                    )
                    drawRect(
                        brush = brush,
                        topLeft = Offset(cometStart, 0f),
                        size = Size(cometWidth, size.height),
                    )
                },
        )
    }
}
