package `in`.jphe.storyvox.feature.notes.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.NotesRepository
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import `in`.jphe.storyvox.feature.notes.record.AudioRecorder
import `in`.jphe.storyvox.feature.notes.record.RecordingService
import `in`.jphe.storyvox.playback.transcribe.offline.TranscriptionScheduler
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Immutable snapshot of the [RecordScreen] — timer, waveform, transport state. */
@Immutable
data class RecordUiState(
    val elapsedMs: Long = 0L,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    /** Recent normalized amplitudes in `[0f, 1f]`, oldest → newest, for the waveform. */
    val amplitudes: List<Float> = emptyList(),
    val isSaving: Boolean = false,
)

/** One-shot events from the record VM to its screen. */
sealed interface RecordEvent {
    /** The recording was saved as a note — the screen opens its detail. */
    data class Saved(val noteId: String) : RecordEvent
}

/**
 * Voice Notes (#1657) — ViewModel for the [RecordScreen]; the spec's
 * "RecordingController" (§3.1). Phase 4 (luna) shipped the final chrome against a
 * deterministic simulation; **Phase 2a swaps in real capture** via [AudioRecorder]
 * (`MediaRecorder` → `filesDir/recordings/<id>.m4a`) under a microphone-typed
 * [RecordingService], and persists on stop.
 *
 * The heavy state (open recorder + output file + elapsed clock) lives in the
 * `@Singleton` [AudioRecorder], so a configuration change that recreates this VM
 * re-attaches to the still-running take ([init]) instead of dropping it. The VM
 * itself only owns the display ticker + the persistence-on-stop flow.
 *
 * The `RECORD_AUDIO` runtime permission is gated by [RecordScreen] before
 * [start] is ever called (kept out of the VM so it stays free of Android
 * permission plumbing).
 */
@HiltViewModel
class NotesRecordViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRecorder: AudioRecorder,
    private val notesRepository: NotesRepository,
    private val transcriptionScheduler: TranscriptionScheduler,
    @Named(NotesRepository.RECORDINGS_DIR_QUALIFIER) private val recordingsDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecordEvent>(Channel.BUFFERED)
    val events: Flow<RecordEvent> = _events.receiveAsFlow()

    private var ticker: Job? = null

    init {
        // VM recreated (e.g. rotation) while a take is still running in the
        // singleton recorder — re-attach the UI to it rather than orphan it.
        if (audioRecorder.isRecording) {
            _uiState.value = RecordUiState(
                isRecording = true,
                isPaused = audioRecorder.isPaused,
                elapsedMs = audioRecorder.elapsedMs(),
            )
            startTicker()
        }
    }

    /** Begin a recording. Caller ([RecordScreen]) guarantees RECORD_AUDIO is granted. */
    fun start() {
        if (audioRecorder.isRecording) return
        val noteId = UUID.randomUUID().toString()
        val file = File(recordingsDir, "$noteId.m4a")
        // Promote to the mic FGS first (so background capture is attributed to
        // it), then open the recorder. Roll back the FGS if capture won't start.
        startFgs()
        if (!audioRecorder.start(file)) {
            stopFgs()
            _uiState.value = RecordUiState()
            return
        }
        _uiState.value = RecordUiState(isRecording = true)
        startTicker()
    }

    fun pause() {
        if (!audioRecorder.isRecording) return
        audioRecorder.pause()
        _uiState.update { it.copy(isPaused = true) }
    }

    fun resume() {
        if (!audioRecorder.isRecording) return
        audioRecorder.resume()
        _uiState.update { it.copy(isPaused = false) }
    }

    /** Abandon the in-progress take without persisting anything. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        audioRecorder.discard()
        stopFgs()
        _uiState.value = RecordUiState()
    }

    /**
     * Stop capture and persist it as a PENDING note, then enqueue transcription
     * (Phase 2b) and emit [RecordEvent.Saved]. A too-short/failed take (no usable
     * audio) is silently discarded — no empty note row is created.
     */
    fun stopAndSave() {
        if (_uiState.value.isSaving) return
        ticker?.cancel()
        ticker = null
        _uiState.update { it.copy(isRecording = false, isPaused = false, isSaving = true) }

        // Capture the file BEFORE stop() (which clears it); derive the id from the
        // filename so this survives a VM recreation mid-take.
        val file: File? = audioRecorder.currentOutput
        val durationMs = audioRecorder.stop()
        stopFgs()

        val noteId = file?.nameWithoutExtension
        if (durationMs < 0L || file == null || noteId == null) {
            _uiState.value = RecordUiState()
            return
        }

        val now = System.currentTimeMillis()
        viewModelScope.launch {
            notesRepository.upsert(
                NoteEntity(
                    id = noteId,
                    title = "",
                    createdAt = now,
                    updatedAt = now,
                    audioPath = file.absolutePath,
                    durationMs = durationMs,
                    // Phase 2b's worker advances PENDING → RUNNING → DONE/FAILED.
                    transcriptionStatus = TranscriptionStatus.PENDING,
                ),
            )
            // Close the P2b enqueue seam — the scheduler's KDoc names Phase 2a as
            // its caller. KEEP-unique, so a UI retry later is a no-op not a dupe.
            transcriptionScheduler.enqueue(noteId)
            _uiState.update { it.copy(isSaving = false) }
            _events.send(RecordEvent.Saved(noteId))
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val paused = audioRecorder.isPaused
                val amp = if (paused) 0f else AudioRecorder.normalizeAmplitude(audioRecorder.maxAmplitude)
                val elapsed = audioRecorder.elapsedMs()
                _uiState.update { s ->
                    s.copy(
                        isRecording = true,
                        isPaused = paused,
                        elapsedMs = elapsed,
                        amplitudes = (s.amplitudes + amp).takeLast(MAX_BARS),
                    )
                }
            }
        }
    }

    private fun startFgs() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun stopFgs() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
    }

    override fun onCleared() {
        ticker?.cancel()
        // If the screen is torn down (not merely rotated) mid-take, abandon it —
        // don't leave a mic FGS running with no UI. (Rotation recreates the VM
        // synchronously, so isRecording re-attaches in init before this matters.)
        if (audioRecorder.isRecording) {
            audioRecorder.discard()
            stopFgs()
        }
        super.onCleared()
    }

    private companion object {
        /** 10 Hz update — smooth waveform + timer without churn. */
        const val TICK_MS = 100L
        /** Rolling window of bars kept for the waveform. */
        const val MAX_BARS = 48
    }
}

/**
 * Deterministic pseudo-amplitude in `[0.08f, 1f]` — retained for the [RecordScreen]
 * Compose previews (stable sample data). No longer used by the live recorder
 * (Phase 2a feeds real mic amplitude).
 */
internal fun simulatedAmplitude(tick: Int): Float {
    val a = sin(tick * 0.35).toFloat()
    val b = sin(tick * 0.11 + 1.3).toFloat()
    val mixed = a * 0.6f + b * 0.4f
    return (0.08f + 0.92f * abs(mixed)).coerceIn(0.08f, 1f)
}
