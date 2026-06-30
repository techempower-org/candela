package `in`.jphe.storyvox.wear.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.wear.R
import `in`.jphe.storyvox.wear.theme.WearLibraryNocturneTheme

/**
 * Low-power always-on (ambient) now-playing view. Shown while the watch
 * is dimmed; [WearAppRoot] swaps back to the full interactive UI on wake.
 *
 * Follows Wear's ambient rendering guidance: a black background, a single
 * dim greyscale foreground (low-bit panels quantise colour, so the brass
 * palette and cover artwork are dropped), and as few lit pixels as
 * possible — only the time ([TimeText]), a play/pause status glyph, and
 * the chapter/book title survive. On panels that report burn-in risk the
 * content wanders a few pixels each ambient tick to spread wear.
 *
 * Anti-aliasing isn't disabled per the classic Canvas-ambient advice —
 * Compose `Text` doesn't expose that — but the black-on-grey, minimal-pixel
 * composition meets the same low-power intent.
 *
 * Stateless and self-contained (no [androidx.wear.compose.foundation.AmbientModeManager]
 * lookup) so it stays previewable; the caller supplies the synced state,
 * the resolved [AmbientMode.Ambient], and the monotonically increasing
 * ambient [tick].
 */
@Composable
internal fun AmbientNowPlaying(
    state: PlaybackState,
    ambient: AmbientMode.Ambient,
    tick: Int,
) {
    // Burn-in shift: only on panels that ask for it. x cycles each tick, y
    // advances once per x-cycle, so the content covers a small 2D grid over
    // time rather than wandering a single diagonal.
    val protect = ambient.isBurnInProtectionRequired
    val xShift = if (protect) (tick % BURN_IN_SHIFT_SPAN) - BURN_IN_SHIFT_HALF else 0
    val yShift = if (protect) ((tick / BURN_IN_SHIFT_SPAN) % BURN_IN_SHIFT_SPAN) - BURN_IN_SHIFT_HALF else 0

    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .offset(x = xShift.dp, y = yShift.dp)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = AmbientForeground,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = state.chapterTitle ?: state.bookTitle ?: stringResource(R.string.wear_app_name),
                    color = AmbientForeground,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Dim grey foreground — readable on a dimmed OLED panel while staying
 *  low-brightness for power + burn-in, and safe under low-bit quantisation. */
private val AmbientForeground = Color(0xFFB0B0B0)

/** Pixel span the burn-in shift wanders across (−half .. +half-1). */
private const val BURN_IN_SHIFT_SPAN = 6
private const val BURN_IN_SHIFT_HALF = 3

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Ambient · Playing")
@Composable
private fun PreviewAmbientPlaying() {
    WearLibraryNocturneTheme {
        AmbientNowPlaying(
            state = PlaybackState(
                isPlaying = true,
                bookTitle = "The Brass Compendium",
                chapterTitle = "Chapter 7 · The Long Stair",
                durationEstimateMs = 600_000L,
                charOffset = 22_500,
                speed = 1.0f,
            ),
            ambient = AmbientMode.Ambient(
                isBurnInProtectionRequired = true,
                isLowBitAmbientSupported = false,
            ),
            tick = 0,
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Ambient · Paused")
@Composable
private fun PreviewAmbientPaused() {
    WearLibraryNocturneTheme {
        AmbientNowPlaying(
            state = PlaybackState(
                isPlaying = false,
                bookTitle = "Lyra & the Lantern",
                chapterTitle = "Chapter 1 · Sparks",
                durationEstimateMs = 900_000L,
                charOffset = 0,
                speed = 1.0f,
            ),
            ambient = AmbientMode.Ambient(
                isBurnInProtectionRequired = false,
                isLowBitAmbientSupported = true,
            ),
            tick = 0,
        )
    }
}
