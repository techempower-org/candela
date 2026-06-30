package `in`.jphe.storyvox.wear.tile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.tiles.TileService
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import kotlinx.coroutines.launch

/**
 * Invisible trampoline for the [PlaybackTileService] play/pause button.
 *
 * A Protolayout `Clickable` can only launch an Activity or reload the tile — it
 * can't send a data-layer message. So the tile's play/pause click launches this
 * activity with the chosen `CMD_*` path in [EXTRA_CMD]; we forward it to the
 * phone via [WearPlaybackBridge.send] (which only needs the lazy node/message
 * clients — no `start()`), ask the system to refresh the tile so the icon flips
 * immediately, and finish without ever drawing UI.
 *
 * Declared with a translucent theme + `excludeFromRecents` in the manifest so
 * the user only ever sees the tile, never this shim.
 */
class PlaybackTileCommandActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent?.getStringExtra(EXTRA_CMD)
        if (command.isNullOrBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            // Best-effort: a failed send already flips the bridge's connectivity
            // state; the tile simply re-renders from the next synced PlaybackState.
            runCatching { WearPlaybackBridge(applicationContext).send(command) }
            runCatching {
                TileService.getUpdater(applicationContext)
                    .requestUpdate(PlaybackTileService::class.java)
            }
            finish()
        }
    }
}
