package `in`.jphe.storyvox.playback

import android.media.AudioManager

/**
 * Issue #1769 — the audio-focus state machine, split out of
 * [AudioFocusController] as pure functions so it can be pinned by plain
 * JVM tests.
 *
 * Pre-#1769 the controller tracked a single `held: Boolean`, and
 * `AudioOutputMonitor` read `!held` as "another app took focus". But
 * `held` is also false before the first `acquire()` and after an
 * `abandon()`, and `EnginePlayer.loadAndPlay` publishes `isPlaying = true`
 * several seconds before `startPlaybackPipeline()` acquires focus (it
 * loads the voice model in between: 30 s or more on a Tab A7 Lite). For
 * that whole window the monitor reported `WaitReason.FocusLost`,
 * "Paused for a call", with no call and an empty focus stack, while the
 * MediaSession read PLAYING. The distinct states below keep "we have not
 * asked yet" apart from "the framework took it from us".
 */
enum class AudioFocusStatus {
    /** Never requested, or abandoned by us. Nothing was taken from us. */
    NotRequested,

    /** The framework granted our request (or returned focus with GAIN). */
    Held,

    /** AUDIOFOCUS_LOSS_TRANSIENT: a call, an alarm, a recorder. */
    LostTransient,

    /** AUDIOFOCUS_LOSS: another media app took focus for good. */
    Lost,

    /** Our request was refused (FAILED / DELAYED / unknown result). */
    Denied,
}

/** Inputs to [nextFocusStatus]. */
sealed interface AudioFocusEvent {
    /** `requestAudioFocus` returned this `AUDIOFOCUS_REQUEST_*` code. */
    data class RequestResult(val result: Int) : AudioFocusEvent

    /** We called `abandonAudioFocusRequest`. */
    data object Abandoned : AudioFocusEvent

    /** The listener received this `AUDIOFOCUS_*` change code. */
    data class Change(val focusChange: Int) : AudioFocusEvent
}

/**
 * Pure transition function. Framework integer codes are passed through
 * unchanged so the caller does no translation of its own.
 */
fun nextFocusStatus(current: AudioFocusStatus, event: AudioFocusEvent): AudioFocusStatus =
    when (event) {
        is AudioFocusEvent.RequestResult ->
            if (event.result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                AudioFocusStatus.Held
            } else {
                AudioFocusStatus.Denied
            }
        AudioFocusEvent.Abandoned -> AudioFocusStatus.NotRequested
        // A change for a request we have already abandoned (or never
        // made) is stale: the framework queued it before our abandon
        // landed. It says nothing about focus we currently want.
        is AudioFocusEvent.Change -> if (current == AudioFocusStatus.NotRequested) current else when (event.focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> AudioFocusStatus.Held
            AudioManager.AUDIOFOCUS_LOSS -> AudioFocusStatus.Lost
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusStatus.LostTransient
            // Duck: we keep playing at full level (speech), so nothing
            // changes. An unknown code also leaves the state alone.
            else -> current
        }
    }

/**
 * True only when something else actually holds focus that we asked for.
 * This is the one case where the "Paused for a call" card is honest.
 * [AudioFocusStatus.NotRequested] is deliberately false: it is the
 * normal state between a Play tap and the pipeline's `acquire()`.
 */
fun AudioFocusStatus.isTakenByAnotherApp(): Boolean = when (this) {
    AudioFocusStatus.LostTransient,
    AudioFocusStatus.Lost,
    AudioFocusStatus.Denied -> true
    AudioFocusStatus.NotRequested,
    AudioFocusStatus.Held -> false
}

/**
 * Whether a focus-change callback should pause playback. Only a real
 * loss of a live request pauses; a duck (speech keeps playing), a GAIN,
 * and a stale loss that arrives after we abandoned do not. [previous] is
 * the status before the change was applied.
 */
fun shouldPauseOnFocusChange(previous: AudioFocusStatus, focusChange: Int): Boolean =
    previous != AudioFocusStatus.NotRequested &&
        (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
