package `in`.jphe.storyvox.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #1367 (Wear PR1) — single source of truth for Recording mode's
 * remote-able controls, hoisted into core-playback so **both** the phone's
 * `RecordingScreen` and the Wear bridge ([`in`.jphe.storyvox.playback.wear.PhoneWearBridge])
 * can drive one shared state — exactly the move #1308 made for the teleprompter
 * with [TeleprompterController].
 *
 * ## Why a controller at all
 * Recording state otherwise lives in the UI-scoped `RecordingViewModel` + the
 * composition-scoped `CameraRecorder`, neither reachable from `PhoneWearBridge`
 * (core-playback can't see Compose / `:feature`). This `@Singleton` gives the
 * watch a StateFlow contract to reflect (`armed` / `recording` / `elapsedMs`)
 * and an intent channel to drive (`requestStart` / `requestStop`).
 *
 * ## State vs. intent — why a SharedFlow
 * Unlike the teleprompter's purely-stateful toggles, recording start/stop are
 * **events**, not levels: "start" means "run the 3-2-1 countdown then record",
 * which the camera-owning composable must execute. So intents flow one-way over
 * [requests] (a replay-0 [SharedFlow]); the resulting state flows back through
 * the `set*` writebacks. A start emitted while no `RecordingScreen` is
 * collecting (i.e. [armed] is false) reaches no collector and is harmlessly
 * dropped — the watch is expected to gate its button on [armed] and surface
 * "open Recording on your phone first" instead.
 *
 * ## The camera lifecycle constraint
 * The camera (CameraX `VideoCapture`) binds to the phone `RecordingScreen`'s
 * composition lifecycle, so it can only record while that screen is foreground.
 * [armed] encodes exactly that: the `RecordingViewModel` sets it true on init
 * and false in `onCleared`, so the wrist remote is a trigger for an
 * already-framed session, not a background launcher.
 */
@Singleton
class RecordingController @Inject constructor() {

    private val _armed = MutableStateFlow(false)

    /** True only while the phone is on `RecordingScreen` with the camera bound
     *  and ready. The Wear remote gates its record button on this. */
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _recording = MutableStateFlow(false)

    /** True while a clip is actively being captured (between countdown end and
     *  finalize). */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)

    /** Elapsed capture time in ms, so the watch can mirror the REC timer. 0
     *  when not recording. */
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    // replay=0 (intents aren't state), small extra buffer so a trySend from the
    // non-suspend bridge/VM isn't lost if the collector is momentarily busy.
    private val _requests = MutableSharedFlow<RecordingRequest>(extraBufferCapacity = 8)

    /** One-way intent stream the `RecordingScreen` collects to drive the camera.
     *  Emitting with no active collector is a no-op (see class KDoc). */
    val requests: SharedFlow<RecordingRequest> = _requests.asSharedFlow()

    // ── Phone-side state writeback (RecordingViewModel) ──────────────────────

    fun setArmed(armed: Boolean) { _armed.value = armed }

    fun setRecording(recording: Boolean) { _recording.value = recording }

    fun setElapsedMs(elapsedMs: Long) { _elapsedMs.value = elapsedMs }

    // ── Remote / phone intents ───────────────────────────────────────────────

    /** Request the start of a recording (runs the countdown on the phone). Only
     *  effective when a `RecordingScreen` is collecting (i.e. [armed]). */
    fun requestStart() { _requests.tryEmit(RecordingRequest.Start) }

    /** Request that the active recording stop and finalize. */
    fun requestStop() { _requests.tryEmit(RecordingRequest.Stop) }
}

/** A payload-less recording intent carried over [RecordingController.requests]. */
enum class RecordingRequest { Start, Stop }
