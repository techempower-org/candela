package `in`.jphe.storyvox.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #560 (stuck-state-fixer) — owner of the storyvox process's audio
 * focus lifecycle. Pre-fix EnginePlayer never called
 * [AudioManager.requestAudioFocus] for its TTS AudioTrack — on phones
 * with strict audio policy (Samsung Z Flip3 confirmed by the v0.5.57
 * audit), the AudioTrack write loop silently parks at the framework
 * level because the process has no focus claim. MediaSession heartbeats
 * still report `state=PLAYING` (the engine THINKS it's playing) but
 * `dumpsys audio` shows the focus stack empty and the AudioTrack
 * in `state:paused`. That's the audit's "silent stuck state" — the
 * Bug 1 root cause.
 *
 * Lifecycle:
 *  - `acquire()` when the user starts playback and again before starting
 *    an AudioTrack write loop. Idempotent so a chapter advance (which
 *    rebuilds the pipeline) doesn't fight itself.
 *  - `abandon()` on user stop / app death so other media apps can
 *    resume, and before handing playback to the audio-stream ExoPlayer
 *    (which requests its own focus, #1769).
 *  - The focus-change listener routes transient losses (a phone call,
 *    a notification ding) to a pause callback; transient-can-duck
 *    leaves us playing (matches Spotify / Pocket Casts behavior for
 *    speech). Full focus loss is treated as a user-equivalent pause.
 *
 * State lives in [AudioFocusStatus] with the pure transition function
 * [nextFocusStatus] (#1769). Pre-#1769 a single `held` boolean conflated
 * "not requested yet" with "taken by another app".
 *
 * Why a separate Singleton: EnginePlayer is `@AssistedInject` (per-Service
 * instance), but the focus state is process-wide — Android maintains
 * one focus stack per UID, and `requestAudioFocus` deduplicates by
 * AudioFocusRequest object identity. Keeping the request object owned
 * by a Singleton makes the lifecycle predictable across service
 * restarts.
 *
 * API 26+: uses [AudioFocusRequest]. storyvox's minSdk is 26 (per AGP
 * config) so we only ship the modern path; the deprecated streamType-
 * based focus API is not wired here. If minSdk ever rises further, no
 * change needed.
 */
@Singleton
class AudioFocusController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Held so we abandon the SAME request object we acquired. Android's
     * focus stack is keyed on the AudioFocusRequest reference, so a new
     * Builder instance would be a no-op abandon. Single-shot per
     * acquire/abandon pair.
     */
    @Volatile
    private var currentRequest: AudioFocusRequest? = null

    /** Issue #1769 — our view of the framework's focus state. Every
     *  transition goes through the pure [nextFocusStatus]. */
    private val status = AtomicReference(AudioFocusStatus.NotRequested)

    private fun transition(event: AudioFocusEvent): AudioFocusStatus =
        status.updateAndGet { nextFocusStatus(it, event) }

    /**
     * Notification path for focus-change events that the engine should
     * react to. The controller doesn't pause the AudioTrack directly —
     * that's EnginePlayer's job. We just translate the framework's
     * focus-change codes into a single boolean intent ("the user / OS
     * effectively paused us; tear down or duck"). The hookup lives in
     * StoryvoxPlaybackService where EnginePlayer is constructed.
     */
    @Volatile
    private var onFocusLost: (() -> Unit)? = null

    fun setOnFocusLost(callback: (() -> Unit)?) {
        onFocusLost = callback
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val previous = status.getAndUpdate { nextFocusStatus(it, AudioFocusEvent.Change(focusChange)) }
        Log.i(LOG_TAG, "focus change $focusChange: $previous -> ${status.get()}")
        // Another app took permanent or transient focus (phone call,
        // video, alarm, recorder). We're audiobook-style speech (no
        // ducking value), so both mean "pause and yield". A duck leaves
        // us playing; the system mixes our output below the intruder.
        // On GAIN we DO NOT auto-resume: the user taps play (Spotify's
        // podcast behavior).
        if (shouldPauseOnFocusChange(previous, focusChange)) onFocusLost?.invoke()
    }

    /**
     * Request focus for media playback. Returns true if focus was
     * granted (or already held), false if the framework rejected the
     * request. The engine should NOT start its AudioTrack write loop on a
     * `false` return — that's the pre-existing silent-stuck state.
     *
     * Idempotent: a second `acquire()` while focus is held is a no-op.
     */
    fun acquire(): Boolean {
        val am = audioManager ?: run {
            Log.w(LOG_TAG, "AudioManager unavailable — assuming focus granted")
            transition(AudioFocusEvent.RequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED))
            return true
        }
        if (status.get() == AudioFocusStatus.Held && currentRequest != null) {
            // Already holding focus from an earlier acquire(). Avoid the
            // duplicate request — the framework would just re-emit GAIN
            // and we'd return true anyway, but the log noise is
            // confusing during chapter transitions.
            return true
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    // CONTENT_TYPE_SPEECH would tell the system this is
                    // narration — better routing on some devices for
                    // Bluetooth headsets that have a speech bias. But on
                    // Samsung firmware the speech path also activates
                    // SoundAlive speech-DSP that fights our voice
                    // engine's already-correct pitch. CONTENT_TYPE_MUSIC
                    // matches what the AudioTrack itself advertises (see
                    // EnginePlayer.createAudioTrack) so the focus
                    // attributes don't disagree with the track-level
                    // attributes; consistent attributes minimize routing
                    // re-decisions mid-stream.
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setWillPauseWhenDucked(false) // see ducking comment above
            .build()
        val result = am.requestAudioFocus(request)
        return when (transition(AudioFocusEvent.RequestResult(result))) {
            AudioFocusStatus.Held -> {
                Log.i(LOG_TAG, "focus granted")
                currentRequest = request
                true
            }
            else -> {
                // FAILED: another app (typically a call) holds focus.
                // DELAYED shouldn't fire (setAcceptsDelayedFocusGain(false));
                // it and an unknown result are treated as denied.
                Log.w(LOG_TAG, "focus request not granted (result=$result) — pipeline will be silent")
                false
            }
        }
    }

    /**
     * Release focus so other media apps can resume. Safe to call
     * repeatedly. Always returns the state to
     * [AudioFocusStatus.NotRequested], including after a denial.
     */
    fun abandon() {
        val am = audioManager
        val request = currentRequest
        if (am != null && request != null) {
            runCatching { am.abandonAudioFocusRequest(request) }
                .onFailure { Log.w(LOG_TAG, "abandon threw: ${it.message}") }
        }
        currentRequest = null
        transition(AudioFocusEvent.Abandoned)
    }

    /** True if we currently believe we hold focus. */
    fun isHeld(): Boolean = status.get() == AudioFocusStatus.Held

    /** Issue #1769 — the full state, for diagnostics and logs. */
    fun status(): AudioFocusStatus = status.get()

    /**
     * Issue #1769 — true only when focus we asked for is held by
     * something else (a loss, or a refused request). Never true merely
     * because we have not requested focus yet, which is the normal state
     * between a Play tap and the pipeline's `acquire()`. This is the only
     * condition under which "Paused for a call" is honest.
     */
    fun isTakenByAnotherApp(): Boolean = status.get().isTakenByAnotherApp()

    companion object {
        private const val LOG_TAG = "AudioFocusController"
    }
}
