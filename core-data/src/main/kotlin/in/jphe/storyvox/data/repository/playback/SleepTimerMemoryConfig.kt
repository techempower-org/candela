package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1119 — sleep timer duration memory + progressive extension.
 *
 * Tracks two separate timer duration contexts:
 * - Manual: User-initiated sleep timer (Settings → Voice & Playback default)
 * - Bedtime: Auto-triggered by system (when DND / Bedtime mode activates)
 *
 * Listeners have different sleep patterns depending on context. A bedroom
 * tablet might default to 30 min (slower wind-down), while a commute phone
 * uses 15 min (quick nap). This contract preserves both preferences so the
 * UI surface can offer the most-likely duration by context.
 *
 * Additionally tracks extension history (how many times the user has shaken
 * to extend in the current timer session) to implement progressive extension:
 * instead of always extending by the same duration, offer increasingly longer
 * extensions (5→10→15→30) as the user shakes multiple times.
 *
 * Per-device (NOT synced) — ergonomics are deeply personal.
 *
 * Implementations read from the same DataStore that hosts the rest of
 * [UiSettings]. The `:app` module's `SettingsRepositoryUiImpl`
 * implements this alongside the existing contracts; one DataStore,
 * many contracts.
 */
interface SleepTimerMemoryConfig {

    /** User-initiated sleep timer duration in minutes (not auto-triggered). */
    val manualTimerMinutes: Flow<Int>

    /** Auto-triggered (bedtime-mode) sleep timer duration in minutes. */
    val bedtimeTimerMinutes: Flow<Int>

    /** Number of shake-extends in the current timer session (resets on new timer). */
    val extensionCount: Flow<Int>

    /**
     * Snapshot read for manual timer minutes. Implementations should return
     * the most-recent value, falling back to 15 if the underlying store hasn't
     * emitted yet.
     */
    suspend fun currentManualTimerMinutes(): Int

    /**
     * Snapshot read for bedtime timer minutes. Implementations should return
     * the most-recent value, falling back to 30 if the underlying store hasn't
     * emitted yet (slower wind-down context).
     */
    suspend fun currentBedtimeTimerMinutes(): Int

    /** Save manual timer duration preference. */
    suspend fun setManualTimerMinutes(minutes: Int)

    /** Save bedtime timer duration preference. */
    suspend fun setBedtimeTimerMinutes(minutes: Int)

    /** Increment extension count (called on each shake during fade tail). */
    suspend fun incrementExtensionCount()

    /** Reset extension count (called when new timer is armed). */
    suspend fun resetExtensionCount()

    /**
     * Query the next progressive extension duration based on current count.
     * Returns progressively longer durations: 5 (count=1), 10 (count=2),
     * 15 (count=3), 30 (count=4+).
     */
    suspend fun nextProgressiveExtensionMinutes(): Int
}
