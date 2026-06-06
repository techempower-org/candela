package `in`.jphe.storyvox.wear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
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
import `in`.jphe.storyvox.wear.components.ChapterCover
import `in`.jphe.storyvox.wear.components.CircularScrubber
import `in`.jphe.storyvox.wear.components.LinearScrubber
import `in`.jphe.storyvox.wear.components.TransportRow
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.ParchmentOnMuted
import `in`.jphe.storyvox.wear.theme.WearLibraryNocturneTheme
import kotlinx.coroutines.launch

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
 * Audio plumbing — MediaSession state + transport — is unchanged; we still
 * read from [WearPlaybackBridge] and send `PhoneWearBridge.CMD_*` over
 * MessageClient. This PR is pure UI polish per the #192 spec.
 */
@Composable
fun NowPlayingScreen(bridge: WearPlaybackBridge) {
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
        // #1031 — tap the ring to scrub; duration comes from the synced state.
        onScrub = { fraction -> scope.launch { bridge.sendSeek(fraction, state.durationEstimateMs) } },
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
) {
    val configuration = LocalConfiguration.current
    val isRound = configuration.isScreenRound
    val progress = state.scrubProgress()

    Scaffold(
        timeText = { TimeText() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                )
            } else {
                SquareNowPlaying(
                    state = state,
                    progress = progress,
                    connected = connected,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                )
            }
        }
    }
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
) {
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
                .size(116.dp)
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
        TransportRow(
            isPlaying = state.isPlaying,
            enabled = connected,
            onPlayPause = onPlayPause,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
        )
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
        LinearScrubber(progress = progress, modifier = Modifier.fillMaxWidth())
        TransportRow(
            isPlaying = state.isPlaying,
            enabled = connected,
            onPlayPause = onPlayPause,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
        )
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
            text = state.chapterTitle ?: state.bookTitle ?: "storyvox",
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
        text = "Phone not connected",
        style = MaterialTheme.typography.caption2,
        color = ParchmentOnMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
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

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Small Round")
@Composable
private fun PreviewSmallRound() = NowPlayingPreview(samplePlaying())

@Preview(device = WearDevices.SQUARE, showSystemUi = true, name = "Square")
@Composable
private fun PreviewSquare() = NowPlayingPreview(samplePlaying())

// endregion
