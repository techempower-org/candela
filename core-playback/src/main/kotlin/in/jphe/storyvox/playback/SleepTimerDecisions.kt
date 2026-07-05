package `in`.jphe.storyvox.playback

/**
 * Pure, Android-free decision functions for the sleep-timer auto-arm
 * (#1574) and shake-to-extend (#150 / #595 / #1595) paths.
 *
 * Extracted from [StoryvoxPlaybackService] so the exact gating logic is
 * unit-testable without instantiating the service (which needs Hilt, a
 * `MediaSession`, and a live process). The service stays a thin Android
 * shell that reads system state, calls these, and logs the decision; the
 * *rules* live here where a JVM test can pin them.
 */

/**
 * #1574 — should entering Do Not Disturb auto-arm the sleep timer?
 *
 * True iff DND just became active AND we're actively playing AND no
 * timer is already running.
 *
 * @param dndActive the interruption filter moved away from
 *   `INTERRUPTION_FILTER_ALL` (DND / Bedtime / Sleep turned on). The
 *   caller derives this from `NotificationManager.currentInterruptionFilter`
 *   so this function stays Android-free — and so the raw filter Int can
 *   be logged at the call site (see the #1574 ② instrumentation).
 * @param isPlaying playback is active. NOTE (#557 invariant): the engine
 *   keeps `isPlaying = true` through buffering / synth gaps and only the
 *   MediaSession surface flips to `STATE_BUFFERING` — so a genuine
 *   `PLAYING(3)` media state implies this is `true`. It is therefore
 *   **not** the cause of a missed arm when the phone shows PLAYING; that
 *   refutes the original #1574 ② hypothesis.
 * @param sleepTimerRunning a timer is already counting down; re-arming
 *   would reset the user's countdown, so we skip.
 */
fun shouldArmBedtimeSleep(
    dndActive: Boolean,
    isPlaying: Boolean,
    sleepTimerRunning: Boolean,
): Boolean = dndActive && isPlaying && !sleepTimerRunning

/**
 * #150 / #1595 — should the accelerometer be listening for a
 * shake-to-extend gesture right now?
 *
 * True iff the user hasn't opted out AND an armed timer is inside its
 * fade tail (`0 <= remaining <= fadeWindowMs`). Outside the fade tail the
 * sensor stays off so long playback sessions don't drain battery. A
 * `null` remaining (no timer) maps to "not listening".
 *
 * The [shakeEnabled] gate is why the #1595 investigation's clobber fix
 * matters: if the engine-state merge resets the toggle to its default,
 * this gate can never observe the user's OFF choice (it was stuck true).
 */
fun shouldListenForShake(
    sleepTimerRemainingMs: Long?,
    shakeEnabled: Boolean,
    fadeWindowMs: Long,
): Boolean = shakeEnabled && (sleepTimerRemainingMs ?: -1L) in 0L..fadeWindowMs

/**
 * #150 / #1595 — when a shake fires, should it extend the timer?
 *
 * Guards the re-arm to the fade tail so a shake registered a beat after
 * the timer already paused (or while no timer is running) is ignored.
 * Mirrors [shouldListenForShake] without the enable gate — the detector
 * only runs while enabled, so a fired callback is already past that
 * check.
 */
fun shouldExtendOnShake(
    sleepTimerRemainingMs: Long?,
    fadeWindowMs: Long,
): Boolean = (sleepTimerRemainingMs ?: -1L) in 0L..fadeWindowMs
