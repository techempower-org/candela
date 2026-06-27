package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1190 — auto-enable Do Not Disturb while a sleep timer is armed.
 *
 * When a listener sets a bedtime sleep timer, optionally flip the device
 * into Do Not Disturb so a late-night notification doesn't wake them; the
 * prior interruption filter is restored when the timer expires or is
 * cancelled.
 *
 * Surfaced as its own contract (mirroring [SleepTimerExtendConfig] /
 * [BedtimeSleepConfig]) so `:core-playback`'s `SleepTimer` /
 * `DndController` can read the user's pref without taking a feature-layer
 * dep.
 *
 * Per-device (NOT synced) — DND is an inherently device-local concern; a
 * bedroom tablet and a commuting phone want different behaviour.
 *
 * Implementations read from the same DataStore that hosts the rest of
 * the UI settings. The `:app` module's `SettingsRepositoryUiImpl`
 * implements this alongside the existing contracts; one DataStore, many
 * contracts.
 */
interface SleepTimerDndConfig {

    /** Live flow of whether DND should auto-enable with the sleep timer. */
    val dndWithSleepTimerEnabled: Flow<Boolean>

    /**
     * Snapshot read for callers that need a single value without
     * subscribing. Implementations should return the most-recent value,
     * falling back to `false` if the underlying store hasn't emitted yet.
     */
    suspend fun currentDndWithSleepTimerEnabled(): Boolean
}
