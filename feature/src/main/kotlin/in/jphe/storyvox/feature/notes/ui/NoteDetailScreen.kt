package `in`.jphe.storyvox.feature.notes.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Voice Notes (epic #1657, Phase 4) — the note detail / editor. Serves a typed
 * note, a recording-backed note, or both:
 *  - audio player (stubbed — Phase 2a wires real playback + tap-segment→seek),
 *  - **read-only** transcript (the immutable ASR source of truth),
 *  - summary + a **Summarize** action (stubbed — Phase 3 wires the consented LLM),
 *  - **editable** body, tags, and delete / export.
 *
 * @param onExit pop back to the list (after Back or a delete).
 */
@Composable
fun NoteDetailScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                NoteDetailEvent.Saved -> snackbarHostState.showSnackbar("Saved")
                NoteDetailEvent.Deleted -> onExit()
                NoteDetailEvent.SummarizeUnavailable ->
                    snackbarHostState.showSnackbar(
                        "Couldn't summarize — set up an AI provider in Settings, then try again.",
                    )
                is NoteDetailEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share note"))
                }
            }
        }
    }

    NoteDetailContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onExit,
        onTitleChange = viewModel::onTitleChange,
        onBodyChange = viewModel::onBodyChange,
        onTagsChange = viewModel::onTagsChange,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onExport = viewModel::export,
        onSummarize = viewModel::summarize,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NoteDetailContent(
    state: NoteDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onSummarize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasRecording = state.audioPath != null || state.durationMs != null

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNewDraft) "New note" else "Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onSave, enabled = state.isDirty) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Title") },
            )

            if (hasRecording) {
                AudioPlayerCard(durationMs = state.durationMs, playable = state.audioPath != null)
            }

            TranscriptSection(
                transcript = state.transcript,
                status = state.transcriptionStatus,
            )

            SummarySection(
                summary = state.summary,
                hasTranscript = state.transcript != null,
                onSummarize = onSummarize,
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = onBodyChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                label = { Text("Notes") },
                placeholder = { Text("Write or edit your note…") },
            )

            OutlinedTextField(
                value = state.tags,
                onValueChange = onTagsChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Tags (comma-separated)") },
            )

            val tagList = remember(state.tags) {
                state.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (tagList.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    tagList.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = {
                                onTagsChange(
                                    normalizeNoteTags(tagList.filterNot { it == tag }.joinToString(", ")),
                                )
                            },
                            label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove tag") },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Delete note?") },
            text = {
                Text(
                    if (hasRecording) {
                        "This permanently deletes the note and its recording. This can't be undone."
                    } else {
                        "This permanently deletes the note. This can't be undone."
                    },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Audio player for a recording-backed note. **Phase-4 stub:** the transport is
 * disabled until [playable] (a real `audioPath`) exists. Phase 2a wires actual
 * playback (play/scrub) and Phase 2b adds tap-segment→seek from the transcript.
 */
@Composable
private fun AudioPlayerCard(durationMs: Long?, playable: Boolean) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                // TODO(#1657 P2a): wire to a MediaPlayer over the note's audioPath.
                onClick = {},
                enabled = playable,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play recording")
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (playable) {
                        "0:00 / ${durationMs?.let(::formatNoteDuration) ?: "0:00"}"
                    } else {
                        "Recording ${durationMs?.let(::formatNoteDuration) ?: ""} · playback arrives soon"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun TranscriptSection(transcript: String?, status: TranscriptionStatus) {
    val spacing = LocalSpacing.current
    when {
        transcript != null -> {
            SectionLabel("Transcript")
            SelectionContainer {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }
        }
        status == TranscriptionStatus.PENDING || status == TranscriptionStatus.RUNNING -> {
            SectionLabel("Transcript")
            Text(
                text = if (status == TranscriptionStatus.RUNNING) "Transcribing…" else "Transcription pending.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status == TranscriptionStatus.FAILED -> {
            SectionLabel("Transcript")
            Text(
                text = "Transcription failed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // NONE / DONE-without-text → no transcript section.
    }
}

@Composable
private fun SummarySection(summary: String?, hasTranscript: Boolean, onSummarize: () -> Unit) {
    val spacing = LocalSpacing.current
    when {
        summary != null -> {
            SectionLabel("Summary")
            SelectionContainer {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }
        }
        hasTranscript -> {
            // Transcript present, no summary yet → offer Summarize (stubbed → Phase 3).
            OutlinedButton(onClick = onSummarize) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Summarize",
                    modifier = Modifier.padding(start = spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

// ── Previews ──────────────────────────────────────────────────────────────

@Preview(name = "Note detail · recording", showBackground = true)
@Preview(name = "Note detail · recording dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun NoteDetailRecordingPreview() {
    LibraryNocturneTheme {
        NoteDetailContent(
            state = NoteDetailUiState(
                id = "1",
                title = "Standup ideas",
                body = "Follow up on the migration plan.",
                tags = "work, standup",
                transcript = "Sync on the release, then talk through the migration plan and the on-device tests.",
                durationMs = 92_000,
                audioPath = "/x/1.m4a",
                transcriptionStatus = TranscriptionStatus.DONE,
                isNewDraft = false,
                isDirty = true,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onTitleChange = {},
            onBodyChange = {},
            onTagsChange = {},
            onSave = {},
            onDelete = {},
            onExport = {},
            onSummarize = {},
        )
    }
}

@Preview(name = "Note detail · typed", showBackground = true)
@Composable
private fun NoteDetailTypedPreview() {
    LibraryNocturneTheme {
        NoteDetailContent(
            state = NoteDetailUiState(
                id = "2",
                title = "Grocery list",
                body = "Oat milk, coffee, lemons, sourdough.",
                isNewDraft = false,
                isDirty = false,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onTitleChange = {},
            onBodyChange = {},
            onTagsChange = {},
            onSave = {},
            onDelete = {},
            onExport = {},
            onSummarize = {},
        )
    }
}
