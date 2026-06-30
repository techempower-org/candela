package `in`.jphe.storyvox.wear.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.BrassRingTrack

/**
 * Brass-skinned linear progress for square Wear faces.
 *
 * Square watches are rare-but-supported per the issue; on these the circular
 * ring around the cover art reads awkwardly inside the rectangular safe area.
 * We fall back to a horizontal brass track. Read-only in v1 (touch-to-seek is
 * a follow-up; same constraint as [CircularScrubber]).
 *
 * The filled fraction is run through [animateFloatAsState] so the position
 * pushes that arrive in discrete steps from the phone bridge sweep smoothly
 * instead of snapping. When [indeterminate] is true (buffering) a brass segment
 * slides across the track on a loop — the square-face analogue of the round
 * [CircularScrubber]'s spinner, so buffering reads the same on both form
 * factors.
 */
@Composable
fun LinearScrubber(
    progress: Float,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false,
) {
    if (indeterminate) {
        IndeterminateLinearScrubber(modifier)
        return
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "linear-scrub-progress",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val mid = size.height / 2f
        val trackEnd = size.width
        // Track
        drawLine(
            color = BrassRingTrack,
            start = Offset(0f, mid),
            end = Offset(trackEnd, mid),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        // Filled portion
        if (animatedProgress > 0f) {
            drawLine(
                color = BrassPrimary,
                start = Offset(0f, mid),
                end = Offset(trackEnd * animatedProgress, mid),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Indeterminate buffering animation for the square scrubber: a ~35%-wide brass
 * segment that sweeps left→right and loops, disappearing off both ends. Kept in
 * its own composable so the [rememberInfiniteTransition] only exists while
 * buffering — when the determinate scrubber is shown, no frame-by-frame
 * animation runs.
 */
@Composable
private fun IndeterminateLinearScrubber(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "linear-scrub-buffer")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "linear-scrub-buffer-head",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val mid = size.height / 2f
        val w = size.width
        // Track
        drawLine(
            color = BrassRingTrack,
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        // Sweeping segment. `head` 0f..1f drives the leading edge; the segment
        // starts off-screen left (-segFrac) and exits off-screen right (1f), so
        // the visible brass slides fully across each cycle.
        val segFrac = 0.35f
        val startFrac = head * (1f + segFrac) - segFrac
        val x0 = startFrac.coerceIn(0f, 1f) * w
        val x1 = (startFrac + segFrac).coerceIn(0f, 1f) * w
        if (x1 > x0) {
            drawLine(
                color = BrassPrimary,
                start = Offset(x0, mid),
                end = Offset(x1, mid),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}
