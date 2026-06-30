package `in`.jphe.storyvox.wear.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.BrassRingTrack
import kotlin.math.atan2

/**
 * Circular brass scrubber for round Wear faces.
 *
 * The chapter cover art sits in the center, the brass ring wraps around it
 * showing playback progress. Stroke width is deliberately chunky (6dp) so the
 * brass reads at a glance from the wrist; ring track is the warm-dark outline
 * variant so the unfilled portion still has presence.
 *
 * Touch-to-scrub (#1031): when [onScrub] is supplied, tapping a point on the
 * ring seeks there. We use **tap** rather than drag on purpose — a drag
 * detector would compete with Wear's edge swipe-to-dismiss system gesture
 * (the hazard the original v1 kdoc warned about). A tap is a discrete down-up
 * with no drag, so the dismiss swipe still passes through untouched. The tap
 * point's clockwise angle from 12-o'clock (where the progress arc starts,
 * `startAngle = 270f`) maps to a 0f..1f fraction. When [onScrub] is null the
 * ring stays display-only, exactly as before.
 *
 * @param progress 0f..1f scrub position, typically from
 *   [in.jphe.storyvox.playback.scrubProgress].
 * @param indeterminate when true (buffering), animates a sweep around the ring
 *   instead of a frozen progress arc — matches the phone's buffering spinner.
 * @param onScrub invoked with the tapped 0f..1f fraction; null = read-only.
 */
@Composable
fun CircularScrubber(
    progress: Float,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false,
    strokeWidth: Dp = 6.dp,
    onScrub: ((fraction: Float) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scrubModifier = if (onScrub != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                onScrub(tapFraction(offset, size.width.toFloat(), size.height.toFloat()))
            }
        }
    } else {
        Modifier
    }
    // Smooth the brass sweep so discrete position pushes from the phone bridge
    // glide instead of jumping. Only consumed by the determinate branch; the
    // indeterminate branch uses CircularProgressIndicator's built-in spin.
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "ring-scrub-progress",
    )
    Box(modifier = modifier.then(scrubModifier), contentAlignment = Alignment.Center) {
        if (indeterminate) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                indicatorColor = BrassPrimary,
                trackColor = BrassRingTrack,
                strokeWidth = strokeWidth,
            )
        } else {
            CircularProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier.fillMaxSize(),
                indicatorColor = BrassPrimary,
                trackColor = BrassRingTrack,
                strokeWidth = strokeWidth,
                startAngle = 270f,
            )
        }
        // Cover artwork sits inside the ring with a small inset so the brass
        // doesn't visually graze the artwork edge.
        Box(
            modifier = Modifier
                .padding(strokeWidth * 2)
                .fillMaxSize()
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * Issue #1031 — map a tap at [offset] inside a [width]×[height] box to a
 * 0f..1f ring fraction. 0f is 12-o'clock (where the progress arc starts,
 * `startAngle = 270f`); the fraction grows clockwise. Pure geometry, no
 * Compose state, so it's unit-testable on the JVM.
 *
 * `atan2(dx, -dy)` is the clockwise angle from the upward vertical: dx points
 * right, -dy points up, so a point directly above center → 0, directly right →
 * +π/2, etc. We fold negative angles into 0..2π then normalize by 2π.
 */
internal fun tapFraction(offset: Offset, width: Float, height: Float): Float {
    val dx = offset.x - width / 2f
    val dy = offset.y - height / 2f
    val twoPi = (2.0 * Math.PI).toFloat()
    var angle = atan2(dx, -dy) // clockwise from 12-o'clock, range (-π, π]
    if (angle < 0f) angle += twoPi
    return (angle / twoPi).coerceIn(0f, 1f)
}
