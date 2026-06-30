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
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.rememberAmbientModeManager
import `in`.jphe.storyvox.playback.SleepTimerMode
import `in`.jphe.storyvox.playback.wear.TeleprompterWpmPayload
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import `in`.jphe.storyvox.wear.screens.AmbientNowPlaying
import `in`.jphe.storyvox.wear.screens.NowPlayingScreen
import `in`.jphe.storyvox.wear.screens.SleepTimerScreen
import `in`.jphe.storyvox.wear.screens.SleepTimerUiState
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
    val ambientManager = rememberAmbientModeManager()
    WearLibraryNocturneTheme {
        val ambient = ambientManager.currentAmbientMode as? AmbientMode.Ambient
        if (ambient != null) {
            val state by bridge.state.collectAsStateWithLifecycle()
            AmbientNowPlaying(state = state, ambient = ambient, tick = 0)
        } else {
            InteractiveContent(bridge)
        }
    }
}

@Composable
private fun InteractiveContent(bridge: WearPlaybackBridge) {
    var showTeleprompter by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    when {
        showTeleprompter -> {
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
                    currentLine = state.teleprompterCurrentLine,
                    nextLine = state.teleprompterNextLine,
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
        }
        showSleepTimer -> {
            val state by bridge.state.collectAsStateWithLifecycle()
            val connected by bridge.connected.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            BackHandler { showSleepTimer = false }
            SleepTimerScreen(
                state = SleepTimerUiState(
                    remainingMs = state.sleepTimerRemainingMs,
                    connected = connected,
                ),
                onPickDuration = { minutes ->
                    scope.launch { bridge.sendSleepSet(SleepTimerMode.Duration(minutes)) }
                    showSleepTimer = false
                },
                onPickEndOfChapter = {
                    scope.launch { bridge.sendSleepSet(SleepTimerMode.EndOfChapter) }
                    showSleepTimer = false
                },
                onCancel = {
                    scope.launch { bridge.sendSleepCancel() }
                    showSleepTimer = false
                },
            )
        }
        else -> {
            NowPlayingScreen(
                bridge = bridge,
                onOpenTeleprompter = { showTeleprompter = true },
                onOpenSleepTimer = { showSleepTimer = true },
            )
        }
    }
}
