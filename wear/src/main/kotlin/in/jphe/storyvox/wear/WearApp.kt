package `in`.jphe.storyvox.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.playback.wear.TeleprompterWpmPayload
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import `in`.jphe.storyvox.wear.screens.NowPlayingScreen
import `in`.jphe.storyvox.wear.screens.TeleprompterRemoteScreen
import `in`.jphe.storyvox.wear.screens.TeleprompterRemoteUiState
import `in`.jphe.storyvox.wear.theme.WearLibraryNocturneTheme
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearAppRoot() }
    }
}

@Composable
fun WearAppRoot() {
    val context = LocalContext.current
    val bridge = remember { WearPlaybackBridge(context.applicationContext) }
    DisposableEffect(bridge) {
        bridge.start()
        onDispose { bridge.stop() }
    }
    WearLibraryNocturneTheme {
        // Issue #1308 — two surfaces: Now Playing (transport) and the
        // teleprompter remote. A flat toggle (no nav library) keeps it light;
        // swipe/system back returns from the remote.
        var showTeleprompter by remember { mutableStateOf(false) }
        if (showTeleprompter) {
            val state by bridge.state.collectAsStateWithLifecycle()
            val connected by bridge.connected.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            BackHandler { showTeleprompter = false }
            TeleprompterRemoteScreen(
                state = TeleprompterRemoteUiState(
                    enabled = state.teleprompterEnabled,
                    playing = state.teleprompterPlaying,
                    wpm = state.teleprompterWpm,
                    connected = connected,
                ),
                onToggleEnabled = { scope.launch { bridge.toggleTeleprompter() } },
                onTogglePlay = { scope.launch { bridge.toggleTeleprompterScroll() } },
                onWpmDelta = { delta ->
                    scope.launch {
                        bridge.sendTeleprompterWpm(
                            TeleprompterWpmPayload.step(state.teleprompterWpm, delta),
                        )
                    }
                },
            )
        } else {
            NowPlayingScreen(
                bridge = bridge,
                onOpenTeleprompter = { showTeleprompter = true },
            )
        }
    }
}
