package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1590 — user-tunable default sleep-timer duration.
 *
 * When a listener arms the sleep timer without picking a specific length
 * (the quick toggle — [PlaybackController.toggleSleepTimer]), the timer
 * runs for this many minutes. Pre-#1590 this was hardcoded at 15 in
 * `PlaybackController`.
 *
 * Surfaced as its own contract (mirroring [PlaybackSkipConfig] /
 * [SleepTimerDndConfig]) so `:core-playback` can read the user's pref
 * without taking a feature-layer dep.
 *
 * Per-device (same posture as the other sleep-timer contracts) — a
 * bedroom tablet and a commuting phone want different wind-down lengths.
 *
 * Implementations read from the same DataStore that hosts the rest of
 * the UI settings; `:app`'s `SettingsRepositoryUiImpl` implements this
 * alongside the existing contracts. One DataStore, many contracts.
 */
interface SleepTimerDefaultConfig {

    /** Live flow of the default sleep-timer duration in minutes. */
    val sleepTimerDefaultMinutes: Flow<Int>

    /**
     * Snapshot read for callers that need a single value without
     * subscribing. Implementations return the most-recent value,
     * falling back to 15 if the underlying store hasn't emitted yet.
     */
    suspend fun currentSleepTimerDefaultMinutes(): Int
}
