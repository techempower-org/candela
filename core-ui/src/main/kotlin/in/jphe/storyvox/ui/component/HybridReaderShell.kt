package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import `in`.jphe.storyvox.ui.R
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

enum class ReaderView { Audiobook, Reader }

/** The pane a switch from the receiver lands on. The shell is two-state,
 *  so the accessibility action always offers the *other* pane. */
internal fun ReaderView.opposite(): ReaderView = when (this) {
    ReaderView.Audiobook -> ReaderView.Reader
    ReaderView.Reader -> ReaderView.Audiobook
}

/**
 * Structural canary for issue #1025 — the [HybridReaderShell] pane switch
 * MUST stay reachable without the horizontal drag gesture. The shell wires
 * a [CustomAccessibilityAction] (announced by its *target* pane) plus a
 * [stateDescription] onto its root so TalkBack and Switch Access users can
 * flip panes via the screen-reader actions menu instead of a precise
 * drag-with-velocity — an input those users can't produce.
 *
 * We can't run a Compose UI test from the unit-test source set (no
 * Robolectric / ComposeTestRule), so this boolean pins the contract the
 * same way [bottomTabBarUsesRoleButtonPlusSelected] does for the dock.
 * Flip to false only after proving an equivalent accessible path on a real
 * device with TalkBack.
 *
 * Pinned by `HybridReaderShellSemanticsTest`.
 */
internal const val hybridReaderShellExposesPaneSwitchAction: Boolean = true

/**
 * Structural canary for issue #1787 — the shell MUST clip to its bounds.
 *
 * Both panes are always composed and simply translated: on the Reader pane
 * the Audiobook pane sits at x = -width, i.e. entirely *outside* the shell
 * on the start side (see [paneOffsetsX]). Compose does not clip drawing or
 * hit-testing to a parent's bounds unless asked, and NavHost / Scaffold
 * don't clip at rest. On a tablet the shell's start edge touches the
 * [SideNavRail] (a Row sibling placed *before* the NavHost, so hit-tested
 * *after* it) — the off-screen AudiobookView's `verticalScroll` column then
 * claimed every tap over the rail and rail navigation silently died while
 * the Reader pane was showing. `clipToBounds()` makes the off-pane
 * invisible to both draw and pointer input outside the shell.
 *
 * Pinned by `HybridReaderShellSemanticsTest`. Flip to false only if the
 * panes stop being translated outside the shell (e.g. a pager that
 * doesn't compose the off-screen pane).
 */
internal const val hybridReaderShellClipsToBounds: Boolean = true

/**
 * Pixel x-offsets of the (Audiobook, Reader) panes relative to the shell for
 * a given animated shell offset (0 = Audiobook, -width = Reader). Anything
 * outside `0 until width` lies beyond the shell and must be clipped (#1787).
 */
internal fun paneOffsetsX(animatedOffset: Float, width: Float): Pair<Int, Int> =
    animatedOffset.roundToInt() to (animatedOffset + width).roundToInt()

/**
 * Two-pane horizontal swipe shell.
 *
 * Audiobook lives at offset 0; Reader sits one screen-width to the right.
 * Drag updates an offset in pixels; on release we settle to the nearest pane
 * based on a 40% positional threshold.
 *
 * Implementation note: we use [draggable] + a settle animation rather than
 * `AnchoredDraggableState` to avoid Compose-version churn around its API.
 *
 * @param current which view is currently active (hoisted)
 * @param onViewChange invoked when the user settles on a different view
 */
@Composable
fun HybridReaderShell(
    current: ReaderView,
    onViewChange: (ReaderView) -> Unit,
    audiobookContent: @Composable () -> Unit,
    readerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    // #486 Phase 2 / #480 — settle-spring is functional (it's how the
    // user knows the swipe "completed" into the next pane), so we
    // keep it on, just with a snap spec instead of a 360ms ease.
    // Snap-to-target preserves the structural feedback (the pane
    // does settle) while removing the lateral motion.
    val reducedMotion = LocalReducedMotion.current

    var width by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Target offset — Audiobook=0, Reader=-width
    val target = when (current) {
        ReaderView.Audiobook -> 0f
        ReaderView.Reader -> -width
    }

    val animatedOffset by animateFloatAsState(
        targetValue = if (isDragging) dragOffset else target,
        animationSpec = if (reducedMotion) snap() else tween(motion.swipeDurationMs, easing = motion.swipeEasing),
        label = "shellOffset",
    )

    LaunchedEffect(current, width) {
        if (!isDragging) dragOffset = target
    }

    val draggableState = rememberDraggableState { delta ->
        if (width > 0f) {
            dragOffset = (dragOffset + delta).coerceIn(-width, 0f)
        }
    }

    // #1025 — the drag gesture is invisible to TalkBack / Switch Access,
    // so we mirror the same `onViewChange` flip behind a custom action
    // labelled by the *target* pane (from Audiobook → "Switch to reading
    // view"). The stateDescription tells a focusing screen reader which
    // pane is currently showing.
    val switchTarget = current.opposite()
    val switchActionLabel = when (switchTarget) {
        ReaderView.Reader -> stringResource(R.string.reader_pane_switch_to_reader)
        ReaderView.Audiobook -> stringResource(R.string.reader_pane_switch_to_audiobook)
    }
    val paneStateDescription = when (current) {
        ReaderView.Audiobook -> stringResource(R.string.reader_pane_state_audiobook)
        ReaderView.Reader -> stringResource(R.string.reader_pane_state_reader)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // #1787 — the off-screen pane is translated a full width
            // outside this Box; without the clip it swallows taps on the
            // tablet SideNavRail next to the shell. See
            // [hybridReaderShellClipsToBounds].
            .clipToBounds()
            .semantics {
                stateDescription = paneStateDescription
                customActions = listOf(
                    CustomAccessibilityAction(switchActionLabel) {
                        onViewChange(switchTarget)
                        true
                    },
                )
            }
            .onSizeChanged { size ->
                width = size.width.toFloat()
                if (!isDragging) dragOffset = target
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStarted = { isDragging = true },
                onDragStopped = { velocity ->
                    isDragging = false
                    val settled = settle(dragOffset, width, velocity)
                    if (settled != current) onViewChange(settled)
                    dragOffset = when (settled) {
                        ReaderView.Audiobook -> 0f
                        ReaderView.Reader -> -width
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offsetXBy { paneOffsetsX(animatedOffset, width).first },
        ) { audiobookContent() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offsetXBy { paneOffsetsX(animatedOffset, width).second },
        ) { readerContent() }
    }
}

private fun Modifier.offsetXBy(provider: () -> Int): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(IntOffset(provider(), 0))
        }
    }

/** 40% positional threshold OR strong velocity flips the pane. */
private fun settle(offset: Float, width: Float, velocity: Float): ReaderView {
    if (width <= 0f) return ReaderView.Audiobook
    val pulledFraction = -offset / width
    val velocityFlip = velocity < -800f // fast leftward fling → Reader
    val velocityBack = velocity > 800f  // fast rightward fling → Audiobook
    return when {
        velocityFlip -> ReaderView.Reader
        velocityBack -> ReaderView.Audiobook
        pulledFraction >= 0.4f -> ReaderView.Reader
        else -> ReaderView.Audiobook
    }
}
