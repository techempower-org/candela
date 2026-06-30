package `in`.jphe.storyvox.wear.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.scrubProgress
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import `in`.jphe.storyvox.wear.R
import `in`.jphe.storyvox.wear.components.ChapterCover
import `in`.jphe.storyvox.wear.components.CircularScrubber
import `in`.jphe.storyvox.wear.components.LinearScrubber
import `in`.jphe.storyvox.wear.components.TransportRow
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.BrassTint
import `in`.jphe.storyvox.wear.theme.ParchmentOnMuted
import `in`.jphe.storyvox.wear.theme.WearLibraryNocturneTheme
import kotlinx.coroutines.launch

/** Scroll-pixels per volume step — ~one Galaxy Watch4 bezel detent. Tuned in
 *  the 30–50px detent range so a single physical click steps volume once, while
 *  a smooth touch-ring spin accumulates to the same threshold. */
private const val ROTARY_VOLUME_STEP_PX = 48f

/**
 * Now-playing surface — Library Nocturne styled, circular scrubber on round
 * faces / brass linear scrubber on square faces. Closes #192.
 *
 * Layout (round form factor, the dominant Wear case):
 *
 * ```
 * ┌──────────────────────┐
 * │     [TimeText]       │
 * │                      │
 * │      ╭──────╮        │   ← brass ring (scrubProgress)
 * │     │ cover │       │      EB Garamond chapter title above
 * │      ╰──────╯        │      transport row below
 * │   Chapter Title      │
 * │  Book · Author       │
 * │  ⏮   ⏯   ⏭          │
 * └──────────────────────┘
 * ```
 *
 * On square faces the ring becomes a horizontal brass track at the bottom.
 *
 * Audio plumbing — MediaSession state + transport — reads from
 * [WearPlaybackBridge] and sends `PhoneWearBridge.CMD_*` over MessageClient.
 * #1404 added dual-action skip buttons (long-press = chapter jump via
 * `CMD_PREV_CH` / `CMD_NEXT_CH`) and the elapsed/remaining time readout.
 */
@Composable
fun NowPlayingScreen(
    bridge: WearPlaybackBridge,
    onOpenTeleprompter: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val connected by bridge.connected.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-check reachability whenever the watch face comes back to the
    // foreground, so a phone that returned to range while the screen was off
    // restores the transport controls without a tap.
    LifecycleResumeEffect(bridge) {
        bridge.refreshConnectivity()
        onPauseOrDispose {}
    }

    NowPlayingContent(
        state = state,
        connected = connected,
        onPlayPause = {
            val cmd = if (state.isPlaying) PhoneWearBridge.CMD_PAUSE else PhoneWearBridge.CMD_PLAY
            scope.launch { bridge.send(cmd) }
        },
        onSkipBack = { scope.launch { bridge.send(PhoneWearBridge.CMD_SKIP_BACK) } },
        onSkipForward = { scope.launch { bridge.send(PhoneWearBridge.CMD_SKIP_FWD) } },
        // #1404 — long-press the skip buttons to jump a whole chapter.
        onPrevChapter = { scope.launch { bridge.send(PhoneWearBridge.CMD_PREV_CH) } },
        onNextChapter = { scope.launch { bridge.send(PhoneWearBridge.CMD_NEXT_CH) } },
        // #1031 — tap the ring to scrub; duration comes from the synced state.
        onScrub = { fraction -> scope.launch { bridge.sendSeek(fraction, state.durationEstimateMs) } },
        onOpenTeleprompter = onOpenTeleprompter,
        // #1367 — recording remote. The phone gates these on `recordingArmed`
        // (only effective when it's on RecordingScreen); a Start sent otherwise
        // is dropped phone-side, so the watch only shows the control when armed.
        onStartRecording = { scope.launch { bridge.send(PhoneWearBridge.CMD_RECORDING_START) } },
        onStopRecording = { scope.launch { bridge.send(PhoneWearBridge.CMD_RECORDING_STOP) } },
        onOpenSleepTimer = onOpenSleepTimer,
    )
}

/**
 * Stateless inner content — split out so previews can drive it with a fixed
 * [PlaybackState] without spinning up the real DataClient bridge.
 */
@Composable
internal fun NowPlayingContent(
    state: PlaybackState,
    connected: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onScrub: ((fraction: Float) -> Unit)? = null,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onOpenTeleprompter: () -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val isRound = configuration.isScreenRound
    val progress = state.scrubProgress()

    // Rotating bezel (Galaxy Watch4 Classic) / touch-ring → media volume.
    // Both arrive as RotaryScrollEvent; we accumulate the scroll delta and step
    // the watch's STREAM_MUSIC volume once per detent's worth of travel (so a
    // discrete bezel click steps once, and a smooth touch-ring spin accumulates
    // to the same threshold). Rotary input requires explicit focus on Wear, so
    // we hold a FocusRequester and grab focus once the content is laid out.
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val view = LocalView.current
    val rotaryFocus = remember { FocusRequester() }
    var rotaryAccumPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { rotaryFocus.requestFocus() } }

    Scaffold(
        timeText = { TimeText() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    // Clockwise (positive scroll) raises, counter-clockwise lowers.
                    // while-loops so a fast spin (one large delta) steps more than
                    // once; the sub-detent remainder carries to the next event.
                    rotaryAccumPx += event.verticalScrollPixels
                    while (rotaryAccumPx >= ROTARY_VOLUME_STEP_PX) {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0,
                        )
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        rotaryAccumPx -= ROTARY_VOLUME_STEP_PX
                    }
                    while (rotaryAccumPx <= -ROTARY_VOLUME_STEP_PX) {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0,
                        )
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        rotaryAccumPx += ROTARY_VOLUME_STEP_PX
                    }
                    true
                }
                .focusRequester(rotaryFocus)
                .focusable()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            if (isRound) {
                RoundNowPlaying(
                    state = state,
                    progress = progress,
                    connected = connected,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onScrub = onScrub,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                )
            } else {
                SquareNowPlaying(
                    state = state,
                    progress = progress,
                    connected = connected,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                )
            }
            // Bottom-edge affordances, only when a phone is reachable: the
            // #1367 recording control stacked above the #1308 teleprompter +
            // sleep-timer entries. Anchored off the bottom bezel so they don't
            // crowd the centered transport controls.
            if (connected) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // #1367 — record/stop, shown only while the phone is on
                    // RecordingScreen (recordingArmed). Pulsing dot + mm:ss
                    // while recording.
                    RecordingControl(
                        armed = state.recordingArmed,
                        recording = state.recording,
                        elapsedMs = state.recordingElapsedMs,
                        onStart = onStartRecording,
                        onStop = onStopRecording,
                    )
                    // Teleprompter remote (#1308) + sleep timer entries. Hidden
                    // during an active recording so the Stop affordance is
                    // unambiguous on a tiny screen. caption1 reads at arm's
                    // length; the Sleep label doubles as the live countdown when
                    // a duration timer is running.
                    if (!state.recording) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.wear_teleprompter),
                                color = BrassPrimary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.caption1,
                                modifier = Modifier
                                    .clickable(onClick = onOpenTeleprompter)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                            val sleepRemaining = state.sleepTimerRemainingMs
                            Text(
                                text = if (sleepRemaining != null) {
                                    "Sleep ${formatSleepRemaining(sleepRemaining)}"
                                } else {
                                    "Sleep"
                                },
                                color = if (sleepRemaining != null) BrassTint else BrassPrimary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.caption1,
                                modifier = Modifier
                                    .clickable(onClick = onOpenSleepTimer)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Issue #1367 — wrist record/stop control, bound to the recording state synced
 * from the phone ([PlaybackState.recordingArmed] / `recording` /
 * `recordingElapsedMs`). The camera lives on the phone's RecordingScreen, so:
 *  - **not [armed]** → render nothing (the phone isn't on RecordingScreen, so
 *    there's nothing to drive — the watch shows only the teleprompter entry).
 *  - **[armed], not [recording]** → a red "● Record" tap target.
 *  - **[recording]** → a pulsing red dot + mm:ss elapsed and a "■ Stop" target.
 *
 * Styled as clickable text (matching the teleprompter entry) rather than a
 * filled chip, to sit lightly at the screen's bottom edge.
 */
@Composable
private fun RecordingControl(
    armed: Boolean,
    recording: Boolean,
    elapsedMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val recordColor = MaterialTheme.colors.error
    when {
        recording -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(recordColor)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = formatElapsed(elapsedMs),
                        color = MaterialTheme.colors.onBackground,
                        style = MaterialTheme.typography.caption1,
                    )
                }
                Text(
                    text = "■ Stop",
                    color = recordColor,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier
                        .clickable(onClick = onStop)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        armed -> {
            Text(
                text = "● Record",
                color = recordColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption1,
                modifier = Modifier
                    .clickable(onClick = onStart)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // not armed → nothing (record UI hidden until the phone arms it).
    }
}

/** A red dot that pulses while recording — the universal "REC" cue. */
@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "rec")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "recDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(pulse)
            .clip(CircleShape)
            .background(color),
    )
}

/** mm:ss from elapsed millis (phone sends this second-aligned). */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun RoundNowPlaying(
    state: PlaybackState,
    progress: Float,
    connected: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onScrub: ((fraction: Float) -> Unit)? = null,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
) {
    // Scale the cover+ring to the watch instead of a fixed 116dp — small round
    // faces were crowded (worse now the transport row honours the 48dp touch
    // minimum). ~40% of the face diameter, clamped to a sane range so it stays
    // generous on large faces without pushing the transport off small ones.
    val coverSize = (LocalConfiguration.current.screenWidthDp * 0.40f).dp
        .coerceIn(80.dp, 108.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        CircularScrubber(
            progress = progress,
            indeterminate = state.isBuffering,
            onScrub = onScrub,
            modifier = Modifier
                .size(coverSize)
                .aspectRatio(1f),
        ) {
            ChapterCover(
                coverUri = state.coverUri,
                title = state.bookTitle,
                author = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ChapterMeta(state = state)
        TimeReadout(progress = progress, durationMs = state.durationEstimateMs)
        TransportRow(
            isPlaying = state.isPlaying,
            enabled = connected,
            onPlayPause = onPlayPause,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
            onSkipBackLong = onPrevChapter,
            onSkipForwardLong = onNextChapter,
        )
        ChapterNavHint(connected = connected)
        DisconnectedHint(connected = connected)
    }
}

@Composable
private fun SquareNowPlaying(
    state: PlaybackState,
    progress: Float,
    connected: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        ChapterCover(
            coverUri = state.coverUri,
            title = state.bookTitle,
            author = null,
            modifier = Modifier.size(72.dp),
        )
        ChapterMeta(state = state)
        LinearScrubber(
            progress = progress,
            indeterminate = state.isBuffering,
            modifier = Modifier.fillMaxWidth(),
        )
        TimeReadout(progress = progress, durationMs = state.durationEstimateMs)
        TransportRow(
            isPlaying = state.isPlaying,
            enabled = connected,
            onPlayPause = onPlayPause,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
            onSkipBackLong = onPrevChapter,
            onSkipForwardLong = onNextChapter,
        )
        ChapterNavHint(connected = connected)
        DisconnectedHint(connected = connected)
    }
}

@Composable
private fun ChapterMeta(state: PlaybackState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = state.chapterTitle ?: state.bookTitle ?: stringResource(R.string.wear_app_name),
            style = MaterialTheme.typography.title3,
            color = BrassPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val book = state.bookTitle
        if (!book.isNullOrBlank() && state.chapterTitle != null) {
            Text(
                text = book,
                style = MaterialTheme.typography.caption2,
                color = ParchmentOnMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Brief "Phone not connected" note shown beneath the (greyed) transport row
 * when no phone node is reachable. Reserves no space when connected — it simply
 * isn't composed — so the layout is unchanged in the common case.
 */
@Composable
private fun DisconnectedHint(connected: Boolean) {
    if (connected) return
    Text(
        text = stringResource(R.string.wear_phone_not_connected),
        style = MaterialTheme.typography.caption2,
        color = ParchmentOnMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Elapsed / remaining readout under the scrubber (#1404). Position is derived
 * from [scrubProgress] × duration — the watch never receives an absolute
 * position field, only the char-offset progress fraction the phone publishes.
 * Hidden when there's no duration estimate yet (e.g. a live-audio chapter),
 * where a time pair would be meaningless.
 */
@Composable
private fun TimeReadout(progress: Float, durationMs: Long) {
    if (durationMs <= 0L) return
    val elapsedMs = (progress.coerceIn(0f, 1f) * durationMs).toLong()
    Text(
        text = "${formatPlaybackTime(elapsedMs)} / ${formatPlaybackTime(durationMs)}",
        style = MaterialTheme.typography.caption2,
        color = ParchmentOnMuted,
        textAlign = TextAlign.Center,
    )
}

/**
 * One-line discoverability cue for the skip buttons' long-press chapter jump
 * (#1404) — the gesture is otherwise invisible. Shown only when a phone is
 * reachable (the buttons are disabled otherwise, so the hint would mislead);
 * mutually exclusive with [DisconnectedHint].
 */
@Composable
private fun ChapterNavHint(connected: Boolean) {
    if (!connected) return
    Text(
        text = stringResource(R.string.wear_chapter_nav_hint),
        style = MaterialTheme.typography.caption2,
        color = ParchmentOnMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Format a millisecond duration as a compact wall-clock string: `m:ss` under
 * an hour, `h:mm:ss` at or above one (e.g. `12:30`, `1:00:00`). Built by hand
 * rather than via String.format so it's locale-independent and trivially
 * unit-testable. Negative inputs clamp to zero. (Distinct from [formatElapsed],
 * which is recording-only and never needs the hours field.)
 */
internal fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:$ss"
    } else {
        "$minutes:$ss"
    }
}

// region --- Previews ---

private fun samplePlaying() = PlaybackState(
    isPlaying = true,
    bookTitle = "The Brass Compendium",
    chapterTitle = "Chapter 7 · The Long Stair",
    durationEstimateMs = 600_000L,
    charOffset = 22_500,
    speed = 1.0f,
)

private fun samplePaused() = PlaybackState(
    isPlaying = false,
    bookTitle = "The Brass Compendium",
    chapterTitle = "Chapter 7 · The Long Stair",
    durationEstimateMs = 600_000L,
    charOffset = 5_000,
    speed = 1.0f,
)

private fun sampleBuffering() = PlaybackState(
    isPlaying = true,
    isBuffering = true,
    bookTitle = "Lyra & the Lantern",
    chapterTitle = "Chapter 1 · Sparks",
    durationEstimateMs = 900_000L,
    charOffset = 0,
)

// #1367 — recording-mode states (phone on RecordingScreen).
private fun sampleRecordingArmed() = samplePlaying().copy(recordingArmed = true)

private fun sampleRecording() = samplePlaying().copy(
    recordingArmed = true,
    recording = true,
    recordingElapsedMs = 75_000L,
)

@Composable
private fun NowPlayingPreview(state: PlaybackState, connected: Boolean = true) {
    WearLibraryNocturneTheme {
        NowPlayingContent(
            state = state,
            connected = connected,
            onPlayPause = {},
            onSkipBack = {},
            onSkipForward = {},
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Round · Playing")
@Composable
private fun PreviewRoundPlaying() = NowPlayingPreview(samplePlaying())

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Round · Paused")
@Composable
private fun PreviewRoundPaused() = NowPlayingPreview(samplePaused())

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Round · Buffering")
@Composable
private fun PreviewRoundBuffering() = NowPlayingPreview(sampleBuffering())

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Round · Disconnected")
@Composable
private fun PreviewRoundDisconnected() = NowPlayingPreview(samplePaused(), connected = false)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Round · Record armed")
@Composable
private fun PreviewRecordArmed() = NowPlayingPreview(sampleRecordingArmed())

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Round · Recording")
@Composable
private fun PreviewRecording() = NowPlayingPreview(sampleRecording())

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Small Round")
@Composable
private fun PreviewSmallRound() = NowPlayingPreview(samplePlaying())

@Preview(device = WearDevices.SQUARE, showSystemUi = true, name = "Square")
@Composable
private fun PreviewSquare() = NowPlayingPreview(samplePlaying())

@Preview(device = WearDevices.SQUARE, showSystemUi = true, name = "Square · Buffering")
@Composable
private fun PreviewSquareBuffering() = NowPlayingPreview(sampleBuffering())

// endregion
