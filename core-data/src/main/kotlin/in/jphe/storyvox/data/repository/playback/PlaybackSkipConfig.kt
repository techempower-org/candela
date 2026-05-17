package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issues #593 / #594 — user-tunable skip-forward / skip-back distance
 * + rewind-to-start threshold. Surfaced as its own contract (mirroring
 * [PlaybackBufferConfig] / [PlaybackModeConfig] / [PlaybackResumePolicyConfig])
 * so `:core-playback` can read the user's pref without taking a
 * feature-layer dep.
 *
 * - **Skip distance** drives `PlaybackController.skipForward()` /
 *   `skipBack()` — how many seconds of media-time to seek per tap.
 *   Default 30s matches Spotify / Apple Music / Pocket Casts.
 * - **Rewind-to-start threshold** drives `previousChapter()` — past
 *   this many seconds into a chapter, SkipPrevious rewinds to char 0
 *   of the current chapter instead of jumping to the previous chapter.
 *   0 disables the rewind-to-start behavior entirely.
 *
 * Per-device (not synced) — different ergonomic targets per device.
 *
 * Implementations read from the same DataStore that hosts
 * `UiSettings.skipDistanceSec` / `rewindToStartThresholdSec`. The
 * `:app` module's `SettingsRepositoryUiImpl` implements this alongside
 * the existing `PlaybackBufferConfig` / `PlaybackModeConfig` contracts;
 * one DataStore, multiple contracts.
 */
interface PlaybackSkipConfig {

    /** Live flow of skip distance in seconds. */
    val skipDistanceSec: Flow<Int>

    /**
     * Snapshot read for callers that need a single value without
     * subscribing. Implementations should return the most-recent
     * value, falling back to 30 if the underlying store hasn't
     * emitted yet.
     */
    suspend fun currentSkipDistanceSec(): Int

    /** Live flow of rewind-to-start threshold in seconds. 0 = disabled. */
    val rewindToStartThresholdSec: Flow<Int>

    /**
     * Snapshot read. Falls back to 3 (the Apple Music / Spotify
     * default) if the underlying store hasn't emitted yet.
     */
    suspend fun currentRewindToStartThresholdSec(): Int
}
