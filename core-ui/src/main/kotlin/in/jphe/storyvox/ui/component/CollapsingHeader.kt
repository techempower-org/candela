package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

/**
 * Issue #1195 — a [header] region that scrolls away as the [content]
 * below it is scrolled, reclaiming vertical space on small phones.
 *
 * Library and Browse stack a lot of fixed chrome above their lists —
 * sub-tabs, a search bar, filter chips, a source carousel. On a small
 * device that chrome can eat a third of the viewport before a single
 * result is visible. [CollapsingHeader] wires a [NestedScrollConnection]
 * that translates the header up as the inner scrollable is dragged down
 * (and back as it's dragged up), while the content reflows to fill the
 * space the header vacates.
 *
 * Mechanics:
 * - The header is offset by `[-headerHeight, 0]` px. At `0` it's fully
 *   visible; at `-headerHeight` it has slid entirely off the top.
 * - The content is placed directly below the *visible* portion of the
 *   header, so as the header collapses the content grows to fill the
 *   freed height. This is a layout reflow (the inner list genuinely
 *   gets taller), not a clip — so the list's own scroll range stays
 *   correct.
 * - Only vertical scroll that actually moves the header is consumed;
 *   the remainder passes through to the inner list (and to any
 *   pull-to-refresh / outer top-bar behavior layered around this), so
 *   those keep working unchanged.
 *
 * Because the header is always composed (it's just translated), it stays
 * interactive in every content state — including non-scrolling states
 * like empty/error screens, where there's nothing to scroll and the
 * header simply stays expanded. That's what lets Browse keep its source
 * carousel and tab row reachable even when the current listing is empty.
 *
 * @param resetKey when this value changes, the header snaps back to fully
 *   expanded. Pass the identity of the content surface (e.g. the selected
 *   tab / source) so switching surfaces always reveals the full chrome
 *   rather than inheriting the previous list's collapse state. Must be a
 *   value with stable structural equality (a String or enum) so it
 *   doesn't spuriously re-trigger every recomposition.
 */
@Composable
fun CollapsingHeader(
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
) {
    val state = remember { CollapsingHeaderState() }

    // Re-expand whenever the caller's content surface changes (tab /
    // source switch) so the user lands on fresh, fully-visible chrome
    // instead of mid-collapse from the previous list's scroll. Programmatic
    // scroll-to-top on the inner list doesn't dispatch nested scroll, so
    // this is the hook that keeps the two in sync.
    LaunchedEffect(resetKey) { state.offset.floatValue = 0f }

    Layout(
        // clipToBounds so the header doesn't overdraw above its own bounds
        // (into the top bar / status area) while it translates up to collapse.
        modifier = modifier.clipToBounds().nestedScroll(state.connection),
        content = {
            // A Column so multiple stacked header rows (tabs + search +
            // chips) lay out vertically; a Box would overlap them.
            Column { header() }
            Box { content() }
        },
    ) { measurables, constraints ->
        // Header is free to be as tall as it wants; clamp its minimums so
        // it wraps content height but still fills the available width.
        val headerPlaceable = measurables[0].measure(constraints.copy(minHeight = 0))
        state.headerHeight = headerPlaceable.height

        val offset = state.offset.floatValue.roundToInt()
        val visibleHeader = (headerPlaceable.height + offset).coerceAtLeast(0)

        val contentMaxHeight = (constraints.maxHeight - visibleHeader).coerceAtLeast(0)
        val contentPlaceable = measurables[1].measure(
            constraints.copy(minHeight = 0, maxHeight = contentMaxHeight),
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceable.placeRelative(0, visibleHeader)
            headerPlaceable.placeRelative(0, offset)
        }
    }
}

/**
 * Holds the live collapse [offset] (snapshot-backed so a change remeasures
 * the [CollapsingHeader]) plus the last-measured [headerHeight] (a plain
 * field, constant after first layout, read by [connection] to clamp).
 */
private class CollapsingHeaderState {
    /** Header translation in px, clamped to `[-headerHeight, 0]`. */
    val offset = mutableFloatStateOf(0f)

    /** Measured header height in px; written during layout. */
    var headerHeight: Int = 0

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val limit = -headerHeight.toFloat()
            // Nothing to collapse (header unmeasured or zero-height).
            if (limit >= 0f) return Offset.Zero
            val old = offset.floatValue
            val new = (old + available.y).coerceIn(limit, 0f)
            if (new == old) return Offset.Zero
            offset.floatValue = new
            // Consume only what moved the header; the rest scrolls the list.
            return Offset(0f, new - old)
        }
    }
}
