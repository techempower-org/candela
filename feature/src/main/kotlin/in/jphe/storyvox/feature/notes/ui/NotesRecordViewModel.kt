package `in`.jphe.storyvox.feature.notes.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.NotesRepository
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import java.util.UUID
import javax.inject.Inject
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
 * Voice Notes (epic #1657, Phase 4) — ViewModel for the [RecordScreen].
 *
 * **The recorder is stubbed for Phase 4.** The screen chrome (timer, animated
 * waveform, record / pause / resume / stop / cancel controls, a11y) is final;
 * the elapsed clock + amplitudes come from a deterministic *simulation* here,
 * and Stop persists a note row with **no audio** yet. Phase 2a (morpheus-1619)
 * swaps the simulation for the real `AudioRecorder` (MediaRecorder → `.m4a`
 * under a microphone-typed foreground service) and Phase 2b enqueues the
 * transcription worker. Both swap points are marked with `TODO(#1657 …)`.
 */
@HiltViewModel
class NotesRecordViewModel @Inject constructor(
    private val repo: NotesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecordEvent>(Channel.BUFFERED)
    val events: Flow<RecordEvent> = _events.receiveAsFlow()

    private var ticker: Job? = null
    private var tick = 0

    /** Begin (or restart) a recording session. */
    fun start() {
        if (_uiState.value.isRecording) return
        tick = 0
        _uiState.value = RecordUiState(isRecording = true, isPaused = false)
        // TODO(#1657 P2a): replace this simulated ticker with morpheus-1619's
        //  AudioRecorder — start the MediaRecorder into filesDir/recordings/<id>.m4a
        //  under a microphone-typed FGS and feed real getMaxAmplitude() into the
        //  waveform. The UI below (timer + waveform + transport) is unchanged.
        ticker = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                if (_uiState.value.isPaused) continue
                tick++
                val amp = simulatedAmplitude(tick)
                _uiState.update { s ->
                    s.copy(
                        elapsedMs = s.elapsedMs + TICK_MS,
                        amplitudes = (s.amplitudes + amp).takeLast(MAX_BARS),
                    )
                }
            }
        }
    }

    fun pause() {
        if (_uiState.value.isRecording) _uiState.update { it.copy(isPaused = true) }
    }

    fun resume() {
        if (_uiState.value.isRecording) _uiState.update { it.copy(isPaused = false) }
    }

    /** Abandon the in-progress recording without persisting anything. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        tick = 0
        _uiState.value = RecordUiState()
    }

    /**
     * Stop and persist the recording as a new note row, then emit
     * [RecordEvent.Saved] so the screen opens the note's detail.
     *
     * P4 writes a PENDING row with `audioPath = null` (no capture yet) — a
     * documented placeholder; see the class KDoc + the TODOs below.
     */
    fun stopAndSave() {
        if (_uiState.value.isSaving) return
        val elapsed = _uiState.value.elapsedMs
        ticker?.cancel()
        ticker = null
        _uiState.update { it.copy(isRecording = false, isPaused = false, isSaving = true) }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            repo.upsert(
                NoteEntity(
                    id = id,
                    title = "",
                    createdAt = now,
                    updatedAt = now,
                    // TODO(#1657 P2a): audioPath = the finalized recording path
                    //  from AudioRecorder (filesDir/recordings/<id>.m4a). Null in
                    //  P4 — there is no real capture yet.
                    audioPath = null,
                    durationMs = elapsed,
                    // TODO(#1657 P2b): after insert, enqueue the TranscriptionWorker
                    //  (WorkManager) which advances PENDING → RUNNING → DONE/FAILED
                    //  and streams transcript segments back into the row.
                    transcriptionStatus = TranscriptionStatus.PENDING,
                ),
            )
            _uiState.update { it.copy(isSaving = false) }
            _events.send(RecordEvent.Saved(id))
        }
    }

    override fun onCleared() {
        ticker?.cancel()
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
 * Deterministic pseudo-amplitude in `[0.08f, 1f]` for the Phase-4 stub waveform
 * (two mixed sines give an organic, non-repetitive envelope). Deterministic so
 * previews + any future test are stable; replaced wholesale by real mic
 * amplitude in Phase 2a.
 */
internal fun simulatedAmplitude(tick: Int): Float {
    val a = sin(tick * 0.35).toFloat()
    val b = sin(tick * 0.11 + 1.3).toFloat()
    val mixed = a * 0.6f + b * 0.4f
    return (0.08f + 0.92f * abs(mixed)).coerceIn(0.08f, 1f)
}
