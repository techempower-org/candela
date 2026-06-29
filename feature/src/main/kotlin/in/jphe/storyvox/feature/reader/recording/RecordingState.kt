package `in`.jphe.storyvox.feature.reader.recording

import android.net.Uri

/**
 * Issue #1367 — Recording mode state machine.
 *
 * A standalone recording surface composites the front camera preview behind
 * the semi-transparent teleprompter script (the chapter currently loaded in
 * the reader) so the user can film a YouTube Short / Reel / TikTok while
 * reading. This sealed type is the single source of truth for what the
 * [RecordingScreen] is showing; [RecordingViewModel] owns one
 * `StateFlow<RecordingState>` and drives it through the lifecycle below.
 *
 * The actual CameraX recording is driven out-of-band: the ViewModel emits
 * [RecordingCommand]s that the composable (which owns the lifecycle-bound
 * [CameraRecorder]) executes, then reports back via
 * [RecordingViewModel.onRecordingFinalized] / [RecordingViewModel.onRecordingError].
 * Keeping the camera glue out of the ViewModel keeps the state machine a pure,
 * Android-camera-free unit (testable without a device).
 */
sealed interface RecordingState {

    /** Live camera preview, no recording in flight — the user frames
     *  themselves and reads along (script scrolls only once recording). */
    data object Preview : RecordingState

    /** The 3-2-1 lead-in before recording arms. [secondsLeft] ticks 3 → 1; at
     *  0 the ViewModel transitions to [Recording] and fires
     *  [RecordingCommand.Start]. */
    data class Countdown(val secondsLeft: Int) : RecordingState

    /** Recording is live. [elapsedMs] advances ~10×/sec for the on-screen
     *  timer; the teleprompter script auto-scrolls at the configured WPM. */
    data class Recording(val elapsedMs: Long) : RecordingState

    /** Stop was requested; waiting for CameraX to finalize the MP4 into
     *  MediaStore. Brief — usually a few hundred ms. */
    data object Saving : RecordingState

    /** The clip finalized to the gallery. [uri] is the MediaStore content Uri
     *  (openable / shareable). */
    data class Done(val uri: Uri) : RecordingState

    /** Recording or finalization failed. [message] is user-facing. */
    data class Error(val message: String) : RecordingState
}

/**
 * One-shot instructions from [RecordingViewModel] to the camera-owning
 * composable. Delivered over a buffered channel (collected as a Flow) so a
 * command issued during a recomposition gap isn't dropped.
 */
sealed interface RecordingCommand {

    /** Begin capturing video+audio to a MediaStore entry named [displayName]
     *  (no extension — CameraX appends `.mp4`). */
    data class Start(val displayName: String) : RecordingCommand

    /** Stop the active recording and finalize it. */
    data object Stop : RecordingCommand
}
