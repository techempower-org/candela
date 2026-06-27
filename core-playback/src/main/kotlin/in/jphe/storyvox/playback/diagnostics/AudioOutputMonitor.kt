package `in`.jphe.storyvox.playback.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.AudioFocusController
import `in`.jphe.storyvox.playback.EngineState
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * "Does storyvox KNOW if audio is actually coming out of the speakers?"
 *
 * Pre-monitor the answer was no. The engine reported `EngineState.Playing`
 * the moment it pushed bytes into the AudioTrack write queue, but the
 * framework could silently swallow those bytes (Bug 1 of the v0.5.57
 * audit) — audio focus lost, device muted, route changed under us — and
 * the UI cheerfully painted a Pause icon over dead air.
 *
 * The monitor closes that gap. It runs a 200 ms tick coroutine while
 * playback is active and watches **truthful** signals:
 *
 *  1. The truthful position from [PlaybackController.playbackPositionMs] —
 *     that flow is fed by `AudioTrack.getPlaybackHeadPosition` inside
 *     EnginePlayer, which only advances when the framework actually
 *     consumed frames. If that position stalls while engineState=Playing,
 *     audio is NOT reaching the speakers.
 *  2. [AudioFocusController.isHeld] — process-wide focus state. Lost
 *     focus → known stall.
 *  3. [AudioManager.getStreamVolume] / `isStreamMute` — device muted.
 *  4. [AudioManager.getDevices] + [AudioDeviceCallback] — route changes.
 *  5. [EngineState] — warming / buffering distinction.
 *  6. [PlaybackState.currentChapterId] vs `chapterTitle` — chapter-load
 *     vs sentence-buffer distinction.
 *
 * On every tick the monitor computes a [WaitReason] (or `null` when
 * audio is genuinely flowing) and emits it through [waitReason].
 *
 * "EVERY time, beautifully" — the catch-all path is [WaitReason.AudioOutputStuck]
 * with a `secondsSilent` counter; the panel never goes blank while the
 * pipeline is stuck.
 *
 * Lifecycle: [start] from `StoryvoxPlaybackService.onCreate`, [stop] from
 * `onDestroy`. Idempotent — `start` while already running re-anchors the
 * silence counter.
 */
@Singleton
class AudioOutputMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    /**
     * Lazy provider — [PlaybackController] holds a back-reference to
     * `monitor.waitReason` (so the UI can subscribe through one bus),
     * which would form a constructor-injection cycle if we took
     * PlaybackController directly. The provider breaks the cycle; we
     * resolve it on `start()`, by which point the graph is fully built.
     */
    private val controllerProvider: Provider<PlaybackController>,
    private val audioFocus: AudioFocusController,
) {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _waitReason = MutableStateFlow<WaitReason?>(null)
    /** Null when audio is actually flowing through the speakers. Non-null
     *  with a typed [WaitReason] otherwise. UI subscribes through
     *  [PlaybackController.waitReason] which forwards this flow. */
    val waitReason: StateFlow<WaitReason?> = _waitReason.asStateFlow()

    /** Tick job. Non-null while monitor is running. */
    private var tickJob: Job? = null
    private var stateJob: Job? = null

    /** Wall-clock ms of the most recent position-advance — i.e. the last
     *  moment we have evidence audio actually came out. Tracked by the
     *  tick loop. */
    private val lastAudibleAdvanceMs = AtomicLong(SystemClock.elapsedRealtime())

    /** Last [PlaybackController.playbackPositionMs] value we saw, used
     *  to detect head-position advance between ticks. */
    @Volatile
    private var lastObservedPositionMs: Long = 0L

    /** Wall-clock ms when the current play attempt started. Used to
     *  compute `secondsWaiting` for [WaitReason.NetworkSlow] and similar
     *  time-based payloads. Reset on every `engineState` transition into
     *  Warming or Buffering. */
    @Volatile
    private var phaseStartedAtMs: Long = SystemClock.elapsedRealtime()

    /** Wall-clock ms of the last audio-route-change event from the
     *  [AudioDeviceCallback]. Used to surface
     *  [WaitReason.AudioRouteChange] for ~3 s after the change, then
     *  hand back to whatever the underlying reason is. */
    @Volatile
    private var lastRouteChangeAtMs: Long = 0L

    /** Most-recently-seen voice id from PlaybackState. Cached so a
     *  [WaitReason.WarmingVoice] doesn't have to recompute the label on
     *  every 200 ms tick. */
    @Volatile
    private var lastVoiceLabel: String = "voice"

    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            lastRouteChangeAtMs = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "audio route added — count=${addedDevices?.size}")
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            lastRouteChangeAtMs = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "audio route removed — count=${removedDevices?.size}")
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                lastRouteChangeAtMs = SystemClock.elapsedRealtime()
                Log.i(LOG_TAG, "ACTION_AUDIO_BECOMING_NOISY — headphones unplugged")
            }
        }
    }

    /**
     * Begin monitoring. Safe to call repeatedly — second + subsequent
     * calls just re-anchor the silence counter so a fresh chapter
     * starts with a clean "no silence yet" baseline.
     */
    fun start() {
        // Re-anchor counters; this also covers the "already running"
        // case where stop()/start() bracket a chapter advance.
        val now = SystemClock.elapsedRealtime()
        lastAudibleAdvanceMs.set(now)
        phaseStartedAtMs = now
        lastRouteChangeAtMs = 0L
        if (tickJob?.isActive == true) return

        registerSystemCallbacks()

        val controller = controllerProvider.get()

        stateJob = scope.launch {
            controller.engineState.collect { es ->
                // On every phase transition into Warming or Buffering,
                // re-anchor `phaseStartedAtMs` so the NetworkSlow seconds
                // counter measures the current phase, not the whole
                // play() session.
                if (es is EngineState.Warming || es is EngineState.Buffering) {
                    phaseStartedAtMs = SystemClock.elapsedRealtime()
                }
            }
        }

        tickJob = scope.launch {
            while (isActive) {
                val controller = controllerProvider.get()
                val state = controller.state.value
                val engine = controller.engineState.value
                val pos = controller.playbackPositionMs.value

                // Cache the voice label for WarmingVoice — derived from
                // the voiceId tail segment the same way
                // PlaybackController.warmingMessageForCurrentVoice() does.
                lastVoiceLabel = labelForVoiceId(state.voiceId) ?: lastVoiceLabel

                // Head-position advanced since last tick? That's the
                // only evidence we have that the listener actually heard
                // PCM. Reset the silence anchor; clear the reason.
                if (pos > lastObservedPositionMs) {
                    lastObservedPositionMs = pos
                    lastAudibleAdvanceMs.set(SystemClock.elapsedRealtime())
                    if (engine is EngineState.Playing) {
                        _waitReason.value = null
                        delay(TICK_MS)
                        continue
                    }
                }
                lastObservedPositionMs = pos

                // Derive a reason. Returns null only when audio is
                // flowing AND engine is Playing.
                _waitReason.value = diagnose(state, engine, pos)
                delay(TICK_MS)
            }
        }
        Log.i(LOG_TAG, "monitor started")
    }

    /** Stop monitoring. Safe to call when already stopped. */
    fun stop() {
        tickJob?.cancel()
        tickJob = null
        stateJob?.cancel()
        stateJob = null
        unregisterSystemCallbacks()
        _waitReason.value = null
        Log.i(LOG_TAG, "monitor stopped")
    }

    /**
     * The diagnosis core. Returns null only when audio is genuinely
     * flowing AND engine state is Playing. Otherwise emits a typed
     * [WaitReason] so the panel always has something honest to show.
     *
     * Precedence (highest wins — first match short-circuits):
     *  1. Engine reports Idle / Paused / Completed / Error → no panel
     *     (those states have their own UI surfaces).
     *  2. Recent audio-route change → AudioRouteChange (sticky 3 s).
     *  3. AudioFocus lost → FocusLost.
     *  4. Device muted (vol 0 or stream-mute) → DeviceMuted.
     *  5. EngineState.Warming → WarmingVoice.
     *  6. EngineState.Buffering AND chapterTitle blank → LoadingChapter.
     *  7. EngineState.Buffering with chapter loaded → BufferingNextSentence.
     *  8. Engine reports Playing but head-position stalled for >500 ms →
     *     classify as Network/AudioOutputStuck based on how long.
     */
    internal fun diagnose(
        state: PlaybackState,
        engine: EngineState,
        positionMs: Long,
    ): WaitReason? {
        // 1. No audio expected — paused, idle, completed, error. The
        //    reader UI's existing surfaces (play icon, end-of-book card,
        //    error block) handle these; the diagnostic panel stays hidden.
        when (engine) {
            EngineState.Idle, EngineState.Paused, EngineState.Completed -> return null
            is EngineState.Error -> return null
            else -> Unit
        }

        val now = SystemClock.elapsedRealtime()

        // 2. Audio route changed within the last ROUTE_CHANGE_STICKY_MS.
        //    Sticky so the user gets a beat to see "Audio output changed"
        //    before whatever the next reason is takes over.
        if (lastRouteChangeAtMs > 0L &&
            (now - lastRouteChangeAtMs) < ROUTE_CHANGE_STICKY_MS
        ) {
            return WaitReason.AudioRouteChange
        }

        // 3. Audio focus lost → we asked the framework, the framework
        //    said no. Translate the most-likely-cause (best effort —
        //    Android doesn't expose the focus-stack contents).
        //    Skip for live-audio chapters (#1225): ExoPlayer manages its
        //    own focus (handleAudioFocus=true); AudioFocusController is
        //    only relevant for the TTS AudioTrack path.
        if (!state.isLiveAudioChapter && !audioFocus.isHeld()) {
            return WaitReason.FocusLost(cause = guessFocusCause())
        }

        // 4. Device-wide mute or volume zero. Both are user-actionable.
        audioManager?.let { am ->
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val muted = runCatching {
                am.isStreamMute(AudioManager.STREAM_MUSIC)
            }.getOrDefault(false)
            if (vol == 0 || muted) return WaitReason.DeviceMuted
        }

        // 5. Engine is in its Warming phase — voice model load + first
        //    inference. The label comes from the cached
        //    [lastVoiceLabel] computed at the top of the tick loop.
        if (engine is EngineState.Warming) {
            return WaitReason.WarmingVoice(
                voiceName = lastVoiceLabel,
                progress = engine.progress,
            )
        }

        // 6/7. Engine is Buffering — distinguish chapter-load (no title
        //      yet) from mid-chapter underrun (sentence queue starved).
        if (engine == EngineState.Buffering) {
            val title = state.chapterTitle
            val secondsInPhase = ((now - phaseStartedAtMs) / 1000L).toInt()
            return if (title.isNullOrBlank() || state.currentChapterId == null) {
                // No chapter body yet → loading.
                if (secondsInPhase >= NETWORK_SLOW_THRESHOLD_SEC) {
                    WaitReason.NetworkSlow(secondsWaiting = secondsInPhase)
                } else {
                    WaitReason.LoadingChapter(chapterTitle = title.orEmpty().ifBlank { "next chapter" })
                }
            } else {
                WaitReason.BufferingNextSentence(queueDepth = 0)
            }
        }

        // 8. Engine claims Playing but head-position hasn't advanced for
        //    [STUCK_THRESHOLD_MS]. This is the dangerous silent-stuck
        //    state from the v0.5.57 audit — focus held, volume up, but
        //    no PCM reaching the speakers anyway. We surface it as the
        //    catch-all so the user is never staring at dead air.
        if (engine == EngineState.Playing) {
            val silentMs = now - lastAudibleAdvanceMs.get()
            if (silentMs >= STUCK_THRESHOLD_MS) {
                val seconds = (silentMs / 1000L).toInt().coerceAtLeast(1)
                return WaitReason.AudioOutputStuck(secondsSilent = seconds)
            }
            return null // genuinely playing
        }

        // Fallback — engine in an unknown state. Default to the
        // catch-all so the panel still says SOMETHING. "EVERY time."
        val silentMs = now - lastAudibleAdvanceMs.get()
        val seconds = (silentMs / 1000L).toInt().coerceAtLeast(0)
        return WaitReason.AudioOutputStuck(secondsSilent = seconds)
    }

    /**
     * Best-effort translation of an audio-focus loss into a
     * human-readable cause. Android doesn't expose the focus stack to
     * non-system apps, so we infer from the active audio mode:
     *  - MODE_IN_CALL / MODE_IN_COMMUNICATION → phone call
     *  - MODE_RINGTONE → incoming call ringing
     *  - active alarm stream → alarm
     *  - otherwise → "another app"
     */
    internal fun guessFocusCause(): String {
        val am = audioManager ?: return "another app"
        return when (am.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> "a phone call"
            AudioManager.MODE_RINGTONE -> "an incoming call"
            else -> {
                // Heuristic — if the alarm stream is louder than music,
                // an alarm is likely playing.
                val musicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val alarmVol = runCatching {
                    am.getStreamVolume(AudioManager.STREAM_ALARM)
                }.getOrDefault(0)
                if (alarmVol > musicVol && alarmVol > 0) "an alarm"
                else "another app"
            }
        }
    }

    /** Extract a friendly label from a voice id (e.g. "kokoro-en-US-brian"
     *  → "Brian"). Returns null when the id is null/empty. */
    private fun labelForVoiceId(voiceId: String?): String? {
        if (voiceId.isNullOrBlank()) return null
        val tail = voiceId.substringAfterLast("-", missingDelimiterValue = "")
        if (tail.isBlank() || tail.length !in 2..16) return "voice"
        return tail.replaceFirstChar { it.uppercaseChar() }
    }

    private fun registerSystemCallbacks() {
        val am = audioManager ?: return
        runCatching {
            am.registerAudioDeviceCallback(routeCallback, Handler(Looper.getMainLooper()))
        }.onFailure { Log.w(LOG_TAG, "registerAudioDeviceCallback failed: ${it.message}") }
        runCatching {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(noisyReceiver, filter)
            }
        }.onFailure { Log.w(LOG_TAG, "registerReceiver(BECOMING_NOISY) failed: ${it.message}") }
    }

    private fun unregisterSystemCallbacks() {
        val am = audioManager ?: return
        runCatching { am.unregisterAudioDeviceCallback(routeCallback) }
        runCatching { context.unregisterReceiver(noisyReceiver) }
    }

    companion object {
        private const val LOG_TAG = "AudioOutputMonitor"

        /** Tick cadence. 200 ms = 5 Hz — fast enough that "we noticed audio
         *  stopped" surfaces within a frame or two, slow enough that the
         *  monitor is not a CPU cost while playing. Matches the cadence
         *  spec'd in the architecture brief. */
        internal const val TICK_MS = 200L

        /** Head-position must hold steady for this long before we
         *  classify as stuck. 500 ms is what the architecture brief
         *  spec'd; gives the AudioTrack a beat to refill its internal
         *  buffer without flapping the panel. */
        internal const val STUCK_THRESHOLD_MS = 500L

        /** How long an audio-route change is sticky on the panel — gives
         *  the user a beat to see "Audio output changed" before the
         *  next reason takes over. */
        internal const val ROUTE_CHANGE_STICKY_MS = 3_000L

        /** When buffering a chapter body has run this long, escalate
         *  from LoadingChapter to NetworkSlow. Matches the architecture
         *  brief ("3 s of fetch"). */
        internal const val NETWORK_SLOW_THRESHOLD_SEC = 3
    }
}
