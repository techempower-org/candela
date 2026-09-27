package `in`.jphe.storyvox.feature.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.NotesRepository
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.playback.transcribe.offline.TranscriptionModelProvider
import `in`.jphe.storyvox.playback.transcribe.offline.TranscriptionModelProvider.DownloadProgress
import `in`.jphe.storyvox.playback.transcribe.offline.TranscriptionScheduler
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Voice Notes (#1657) — the missing on-ramp to on-device transcription.
 *
 * [TranscriptionWorker][`in`.jphe.storyvox.playback.transcribe.offline.TranscriptionWorker]
 * deliberately leaves a note `PENDING` when the Whisper model isn't downloaded
 * ("Phase 4 offers the download") — but nothing offered it, so on a fresh
 * install every recording stayed "Transcription pending." forever. This prompt,
 * shown under the transcript on the note detail screen, closes that loop:
 *
 *  - PENDING + no model → **Download transcription model (~200 MB)** with
 *    progress; on completion every PENDING recording is re-enqueued.
 *  - PENDING + model ready → **Transcribe now** (unique-KEEP work, so a note
 *    already queued/running is a no-op).
 *  - FAILED → **Retry transcription**.
 *
 * The decision is the pure [transcriptPromptFor] so it is unit-tested without
 * Compose, WorkManager or the network.
 */

/** Model download state as the prompt sees it (mapped from [DownloadProgress]). */
sealed interface ModelDownloadUi {
    data object Idle : ModelDownloadUi
    /** [fraction] in `[0, 1]`, or null while resolving / size unknown. */
    data class InProgress(val fraction: Float?) : ModelDownloadUi
    data class Failed(val reason: String) : ModelDownloadUi
}

/** What (if anything) to offer beneath a note's transcript. */
sealed interface TranscriptPrompt {
    data object None : TranscriptPrompt
    data object DownloadModel : TranscriptPrompt
    data class Downloading(val fraction: Float?) : TranscriptPrompt
    data class DownloadFailed(val reason: String) : TranscriptPrompt
    data object TranscribeNow : TranscriptPrompt
    data object Retry : TranscriptPrompt
}

/**
 * Pure prompt decision. Typed notes (no audio), notes that already have a
 * transcript, and RUNNING / DONE / NONE states offer nothing. A download in
 * flight (or just failed) takes precedence for a PENDING note, since that's
 * what the user is waiting on.
 */
internal fun transcriptPromptFor(
    hasAudio: Boolean,
    hasTranscript: Boolean,
    status: TranscriptionStatus,
    modelReady: Boolean,
    download: ModelDownloadUi,
): TranscriptPrompt {
    if (!hasAudio || hasTranscript) return TranscriptPrompt.None
    return when (status) {
        TranscriptionStatus.FAILED -> TranscriptPrompt.Retry
        TranscriptionStatus.PENDING -> when {
            download is ModelDownloadUi.InProgress -> TranscriptPrompt.Downloading(download.fraction)
            modelReady -> TranscriptPrompt.TranscribeNow
            download is ModelDownloadUi.Failed -> TranscriptPrompt.DownloadFailed(download.reason)
            else -> TranscriptPrompt.DownloadModel
        }
        else -> TranscriptPrompt.None
    }
}

/** Map a provider progress frame to prompt state. `Done` → Idle (readiness is re-read). */
internal fun DownloadProgress.toUi(): ModelDownloadUi = when (this) {
    DownloadProgress.Resolving -> ModelDownloadUi.InProgress(null)
    is DownloadProgress.Downloading ->
        ModelDownloadUi.InProgress(
            if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else null,
        )
    DownloadProgress.Done -> ModelDownloadUi.Idle
    is DownloadProgress.Failed -> ModelDownloadUi.Failed(reason)
}

/** Recordings still waiting for a first transcription once the model lands. */
internal fun notesAwaitingModel(notes: List<NoteEntity>): List<String> =
    notes.filter { it.audioPath != null && it.transcriptionStatus == TranscriptionStatus.PENDING }
        .map { it.id }

@HiltViewModel
class TranscriptionModelViewModel @Inject constructor(
    private val modelProvider: TranscriptionModelProvider,
    private val scheduler: TranscriptionScheduler,
    private val notes: NotesRepository,
) : ViewModel() {

    private val _modelReady = MutableStateFlow(modelProvider.isReady())
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val _download = MutableStateFlow<ModelDownloadUi>(ModelDownloadUi.Idle)
    val download: StateFlow<ModelDownloadUi> = _download.asStateFlow()

    private var downloadJob: Job? = null

    /** Re-read readiness (e.g. another screen finished the download). */
    fun refresh() { _modelReady.value = modelProvider.isReady() }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            modelProvider.download().collect { p -> _download.value = p.toUi() }
            _modelReady.value = modelProvider.isReady()
            if (_modelReady.value) {
                // Release every recording the worker parked as PENDING for want of a model.
                notesAwaitingModel(notes.observeAll().first()).forEach(scheduler::enqueue)
            }
        }
    }

    /** Transcribe now / retry — unique-KEEP work, so re-tapping is harmless. */
    fun transcribe(noteId: String) = scheduler.enqueue(noteId)
}

@Composable
fun TranscriptionModelPrompt(
    noteId: String,
    hasAudio: Boolean,
    hasTranscript: Boolean,
    status: TranscriptionStatus,
    viewModel: TranscriptionModelViewModel = hiltViewModel(),
) {
    val modelReady by viewModel.modelReady.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    LaunchedEffect(noteId, status) { viewModel.refresh() }

    val prompt = transcriptPromptFor(hasAudio, hasTranscript, status, modelReady, download)
    val spacing = LocalSpacing.current
    when (prompt) {
        TranscriptPrompt.None -> Unit
        TranscriptPrompt.DownloadModel -> Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                stringResource(R.string.notes_transcription_model_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PromptButton(
                label = stringResource(
                    R.string.notes_transcription_model_download,
                    TranscriptionModelProvider.APPROX_TOTAL_MB,
                ),
                retry = false,
                onClick = viewModel::downloadModel,
            )
        }
        is TranscriptPrompt.Downloading -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                stringResource(R.string.notes_transcription_model_downloading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val f = prompt.fraction
            if (f == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            }
        }
        is TranscriptPrompt.DownloadFailed -> Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(
                stringResource(R.string.notes_transcription_model_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            PromptButton(
                label = stringResource(R.string.notes_transcription_model_retry_download),
                retry = true,
                onClick = viewModel::downloadModel,
            )
        }
        TranscriptPrompt.TranscribeNow -> PromptButton(
            label = stringResource(R.string.notes_transcription_transcribe_now),
            retry = false,
            onClick = { viewModel.transcribe(noteId) },
        )
        TranscriptPrompt.Retry -> PromptButton(
            label = stringResource(R.string.notes_transcription_retry),
            retry = true,
            onClick = { viewModel.transcribe(noteId) },
        )
    }
}

@Composable
private fun PromptButton(label: String, retry: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(
            if (retry) Icons.Outlined.Refresh else Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(label, modifier = Modifier.padding(start = LocalSpacing.current.xs))
    }
}
