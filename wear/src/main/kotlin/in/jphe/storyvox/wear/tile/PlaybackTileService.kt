package `in`.jphe.storyvox.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.ARC_ANCHOR_START
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_MEDIUM
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.scrubProgress
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * Glanceable "Now Playing" Tile — the #1 missing Wear surface. Swipe from the
 * watch face for cover/title + a play/pause button + progress, one tap to
 * resume without opening the app.
 *
 * ## How it stays in sync without holding state
 * A [TileService] is a stateless renderer: the system calls [onTileRequest]
 * on demand and we return a layout. So the tile reads the **same**
 * `/playback/state` DataItem that [WearPlaybackBridge] consumes — decoded
 * through the very same [WearPlaybackBridge.decodeState] seam, so the tile and
 * the app can never interpret the wire format differently. A modest freshness
 * interval re-renders periodically; [PlaybackTileCommandActivity] also requests
 * an immediate refresh right after a transport tap so the icon flips at once.
 *
 * ## Why the play/pause tap needs a trampoline
 * Protolayout `Clickable`s can only [ActionBuilders.LaunchAction] (start an
 * activity) or `LoadAction` (reload the tile) — there is no "send a data-layer
 * message" click. So play/pause launches the invisible
 * [PlaybackTileCommandActivity], which forwards the chosen `CMD_*` to the phone
 * over the MessageClient (reusing [WearPlaybackBridge.send]) and finishes. The
 * tile body itself launches `WearMainActivity`.
 */
class PlaybackTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = CallbackToFutureAdapter.getFuture { completer ->
        val job = scope.launch {
            try {
                val tile = TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setFreshnessIntervalMillis(FRESHNESS_MS)
                    .setTileTimeline(
                        TimelineBuilders.Timeline.fromLayoutElement(rootLayout(latestState())),
                    )
                    .build()
                completer.set(tile)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                completer.setException(t)
            }
        }
        completer.addCancellationListener({ job.cancel() }, Runnable::run)
        "PlaybackTileService#onTileRequest"
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        // No bitmap resources — the cover is a brass monogram drawn as text and
        // progress is an Arc, so resources carry only the version stamp.
        completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
        "PlaybackTileService#onTileResourcesRequest"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Read the latest phone-published [PlaybackState] off the cached
     * `/playback/state` DataItem, decoded via the shared
     * [WearPlaybackBridge.decodeState] seam. Falls back to an empty state when
     * the phone has never published or GMS is unavailable, so the tile renders
     * a clean "Nothing playing" rather than failing the request.
     */
    private suspend fun latestState(): PlaybackState {
        val buffer = runCatching { Wearable.getDataClient(this).dataItems.await() }.getOrNull()
            ?: return PlaybackState()
        return try {
            val item = buffer.firstOrNull { it.uri.path == PhoneWearBridge.PATH_STATE }
            val raw = item?.let { DataMapItem.fromDataItem(it).dataMap.getString("state") }
            WearPlaybackBridge.decodeState(raw, PlaybackState())
        } finally {
            buffer.release()
        }
    }

    // ─── layout ─────────────────────────────────────────────────────────────

    private fun rootLayout(state: PlaybackState): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(SURFACE)).build(),
                    )
                    // Tap anywhere but the button → open the full watch app.
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open_app")
                            .setOnClick(launchActivity(WEAR_MAIN_ACTIVITY))
                            .build(),
                    )
                    .build(),
            )
            .addContent(progressRing(state.scrubProgress()))
            .addContent(centerColumn(state))
            .build()

    private fun progressRing(progress: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(270f)) // 12 o'clock
            .setAnchorType(ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(360f))
                    .setThickness(dp(6f))
                    .setColor(argb(RING_TRACK))
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(360f * progress.coerceIn(0f, 1f)))
                    .setThickness(dp(6f))
                    .setColor(argb(BRASS))
                    .build(),
            )
            .build()

    private fun centerColumn(state: PlaybackState): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(20f)).build())
                    .build(),
            )
            .addContent(monogram(state.bookTitle))
            .addContent(spacer(8f))
            .addContent(titleText(bookLine(state), 18f, BRASS_TINT, FONT_WEIGHT_BOLD, maxLines = 2))

        chapterLine(state)?.let { chapter ->
            column.addContent(spacer(2f))
            column.addContent(titleText(chapter, 13f, PARCHMENT_MUTED, FONT_WEIGHT_MEDIUM, maxLines = 1))
        }

        // Progress percent — only when something is loaded (skip the "0%" noise
        // on the empty state).
        if (state.bookTitle != null) {
            column.addContent(spacer(4f))
            column.addContent(
                titleText("${progressPercent(state)}%", 12f, PARCHMENT_MUTED, FONT_WEIGHT_MEDIUM, maxLines = 1),
            )
        }

        column.addContent(spacer(12f))
        column.addContent(playPauseButton(state))
        return column.build()
    }

    /** Brass monogram disc standing in for the cover (the phone's coverUri may be
     *  a content/file URI the watch can't resolve; a real bitmap cover is a
     *  follow-up via the Coil → InlineImageResource path). */
    private fun monogram(book: String?): LayoutElementBuilders.LayoutElement {
        val letter = book?.trim()?.takeIf { it.isNotEmpty() }
            ?.first()?.uppercaseChar()?.toString() ?: "♪"
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(40f))
            .setHeight(dp(40f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(BRASS_MUTED))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(letter)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(20f))
                            .setColor(argb(BRASS_TINT))
                            .setWeight(FONT_WEIGHT_BOLD)
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun titleText(
        text: String,
        sizeSp: Float,
        color: Int,
        weight: Int,
        maxLines: Int,
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(maxLines)
            .setOverflow(TEXT_OVERFLOW_ELLIPSIZE_END)
            .setMultilineAlignment(TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(color))
                    .setWeight(weight)
                    .build(),
            )
            .build()

    private fun playPauseButton(state: PlaybackState): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(BRASS))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(22f)).build())
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(22f))
                            .setEnd(dp(22f))
                            .setTop(dp(10f))
                            .setBottom(dp(10f))
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("play_pause")
                            .setOnClick(
                                launchActivity(
                                    COMMAND_ACTIVITY,
                                    EXTRA_CMD to playPauseCommand(state),
                                ),
                            )
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(if (state.isPlaying) "Pause" else "Play")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(15f))
                            .setColor(argb(SURFACE))
                            .setWeight(FONT_WEIGHT_MEDIUM)
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun spacer(heightDp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build()

    /** Build a [ActionBuilders.LaunchAction] to one of our own activities, with
     *  optional string extras (used to carry the chosen CMD_* to the trampoline). */
    private fun launchActivity(
        className: String,
        vararg extras: Pair<String, String>,
    ): ActionBuilders.LaunchAction {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(className)
        for ((key, value) in extras) {
            activity.addKeyToExtraMapping(
                key,
                ActionBuilders.AndroidStringExtra.Builder().setValue(value).build(),
            )
        }
        return ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MS = 60_000L

        const val WEAR_MAIN_ACTIVITY = "in.jphe.storyvox.wear.WearMainActivity"
        const val COMMAND_ACTIVITY = "in.jphe.storyvox.wear.tile.PlaybackTileCommandActivity"

        // Library Nocturne palette — ARGB mirrors of the Compose tokens in
        // WearLibraryNocturneTheme (Protolayout wants raw ints, not Compose Color).
        const val SURFACE = 0xFF0E0C12.toInt()         // WarmDarkSurface
        const val BRASS = 0xFFB48C5A.toInt()           // BrassPrimary
        const val BRASS_TINT = 0xFFC9A774.toInt()      // BrassTint
        const val BRASS_MUTED = 0xFF3A2A14.toInt()     // BrassMuted
        const val PARCHMENT_MUTED = 0xFFB8AE9F.toInt() // ParchmentOnMuted
        const val RING_TRACK = 0xFF3A3530.toInt()      // BrassRingTrack
    }
}

/** Key for the CMD_* path the tile asks [PlaybackTileCommandActivity] to send. */
internal const val EXTRA_CMD = "in.jphe.storyvox.wear.tile.EXTRA_CMD"

// ─── pure render helpers (unit-tested in PlaybackTileLogicTest) ──────────────

/** Which transport command a play/pause tap should send, given the live state. */
internal fun playPauseCommand(state: PlaybackState): String =
    if (state.isPlaying) PhoneWearBridge.CMD_PAUSE else PhoneWearBridge.CMD_PLAY

/** Primary line: the book title, or a calm empty-state when nothing is loaded. */
internal fun bookLine(state: PlaybackState): String =
    state.bookTitle?.takeIf { it.isNotBlank() } ?: "Nothing playing"

/** Secondary line: the chapter title, or null when absent/blank (row is dropped). */
internal fun chapterLine(state: PlaybackState): String? =
    state.chapterTitle?.takeIf { it.isNotBlank() }

/** Progress as a whole-number percent (0..100), matching the app's scrubber math. */
internal fun progressPercent(state: PlaybackState): Int =
    (state.scrubProgress() * 100f).roundToInt()
