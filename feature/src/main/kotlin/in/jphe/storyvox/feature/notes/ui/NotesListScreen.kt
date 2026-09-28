package `in`.jphe.storyvox.feature.notes.ui

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Voice Notes (epic #1657, Phase 4) — the notes list. A search bar, a feed of
 * note cards (recording/typed glyph · title · snippet · date · duration ·
 * transcription-status chip), a long-press menu (Open / Delete), a primary
 * **Record** FAB, and a top-bar **New note** (typed) action.
 *
 * @param onOpenNote open a note's detail by id (also the "New note" target with
 *   a freshly-minted UUID — the detail seeds a blank draft the first save inserts).
 * @param onRecord open the [RecordScreen].
 */
@Composable
fun NotesListScreen(
    onOpenNote: (String) -> Unit,
    onNewNote: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesListViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    NotesListContent(
        query = query,
        notes = notes,
        onQueryChange = viewModel::onQueryChange,
        onOpenNote = onOpenNote,
        onNewNote = onNewNote,
        onRecord = onRecord,
        onDelete = viewModel::delete,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesListContent(
    query: String,
    notes: List<NoteEntity>,
    onQueryChange: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onNewNote: () -> Unit,
    onRecord: () -> Unit,
    onDelete: (NoteEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    // The note pending a delete confirmation, or null when the dialog is closed.
    // A note delete is destructive to the backing recording (no undo), so it is
    // always gated behind an explicit confirm (see NotesListViewModel KDoc).
    var pendingDelete by remember { mutableStateOf<NoteEntity?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.notes_title), modifier = Modifier.semantics { heading() }) },
                actions = {
                    IconButton(onClick = onNewNote) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.notes_new_note))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                text = { Text(stringResource(R.string.notes_record)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.notes_search_clear))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.notes_search_hint)) },
            )

            if (notes.isEmpty()) {
                NotesEmptyState(
                    isSearching = query.isNotBlank(),
                    query = query,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spacing.md,
                        end = spacing.md,
                        top = spacing.xs,
                        // Clear the FAB so the last row isn't trapped beneath it.
                        bottom = spacing.xxxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            onOpen = { onOpenNote(note.id) },
                            onDelete = { pendingDelete = note },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { note ->
        DeleteNoteDialog(
            note = note,
            onConfirm = {
                onDelete(note)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: NoteEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var menuOpen by remember { mutableStateOf(false) }
    val isRecording = note.audioPath != null || note.durationMs != null
    val snippet = remember(note.body, note.transcript) { noteSnippet(note) }
    val openLabel = stringResource(R.string.notes_open_note)
    val actionsLabel = stringResource(R.string.notes_note_actions)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onClickLabel = openLabel,
                onLongClick = { menuOpen = true },
                onLongClickLabel = actionsLabel,
            ),
    ) {
        Box {
            Row(
                modifier = Modifier.padding(spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Outlined.Mic else Icons.Outlined.Description,
                    contentDescription = stringResource(
                        if (isRecording) R.string.notes_kind_recording else R.string.notes_kind_typed,
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = spacing.xxs)
                        .size(20.dp),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = note.title.ifBlank { stringResource(R.string.notes_untitled) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (snippet.isNotEmpty()) {
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = spacing.xxs),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = DateUtils.getRelativeTimeSpanString(
                                note.updatedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        note.durationMs?.let { ms ->
                            MetaDot()
                            Text(
                                text = formatNoteDuration(ms),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TranscriptionStatusChip(note.transcriptionStatus)
                    }
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_open)) },
                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun MetaDot() {
    Text(
        text = "·",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A small status chip for a note's transcription lifecycle. Renders nothing for
 * [TranscriptionStatus.NONE] (a typed note) and [TranscriptionStatus.DONE] (the
 * transcript itself is the signal) — only the in-flight / failed states earn a chip.
 */
@Composable
internal fun TranscriptionStatusChip(status: TranscriptionStatus) {
    val label = transcriptionStatusLabel(status) ?: return
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/** Label resource for an in-flight/failed transcription, or null when no chip is warranted. */
@StringRes
internal fun transcriptionStatusLabel(status: TranscriptionStatus): Int? = when (status) {
    TranscriptionStatus.PENDING -> R.string.notes_status_pending
    TranscriptionStatus.RUNNING -> R.string.notes_status_running
    TranscriptionStatus.FAILED -> R.string.notes_status_failed
    TranscriptionStatus.NONE, TranscriptionStatus.DONE -> null
}

@Composable
private fun DeleteNoteDialog(
    note: NoteEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasAudio = note.audioPath != null || note.durationMs != null
    val title = note.title.ifBlank { stringResource(R.string.notes_untitled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.notes_delete_title)) },
        text = {
            Text(
                if (hasAudio) {
                    stringResource(R.string.notes_delete_named_with_audio, title)
                } else {
                    stringResource(R.string.notes_delete_named, title)
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.notes_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notes_cancel)) } },
    )
}

@Composable
private fun NotesEmptyState(
    isSearching: Boolean,
    query: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (isSearching) {
                stringResource(R.string.notes_empty_search, query)
            } else {
                stringResource(R.string.notes_empty)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(spacing.xl),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────

private fun sampleNotes(): List<NoteEntity> = listOf(
    NoteEntity(
        id = "1",
        title = "Standup ideas",
        createdAt = 0L,
        updatedAt = System.currentTimeMillis() - 5 * 60_000,
        durationMs = 92_000,
        audioPath = "/x/1.m4a",
        transcript = "Sync on the release, then talk through the migration plan and the on-device tests.",
        transcriptionStatus = TranscriptionStatus.DONE,
    ),
    NoteEntity(
        id = "2",
        title = "",
        createdAt = 0L,
        updatedAt = System.currentTimeMillis() - 3 * 3_600_000,
        durationMs = 14_000,
        audioPath = "/x/2.m4a",
        transcriptionStatus = TranscriptionStatus.RUNNING,
    ),
    NoteEntity(
        id = "3",
        title = "Grocery list",
        createdAt = 0L,
        updatedAt = System.currentTimeMillis() - 2 * 86_400_000,
        body = "Oat milk, coffee, lemons, a good loaf of sourdough.",
        transcriptionStatus = TranscriptionStatus.NONE,
    ),
)

@Preview(name = "Notes list", showBackground = true)
@Preview(name = "Notes list · dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun NotesListPreview() {
    LibraryNocturneTheme {
        NotesListContent(
            query = "",
            notes = sampleNotes(),
            onQueryChange = {},
            onOpenNote = {},
            onNewNote = {},
            onRecord = {},
            onDelete = {},
        )
    }
}

@Preview(name = "Notes list · empty", showBackground = true)
@Composable
private fun NotesListEmptyPreview() {
    LibraryNocturneTheme {
        NotesListContent(
            query = "",
            notes = emptyList(),
            onQueryChange = {},
            onOpenNote = {},
            onNewNote = {},
            onRecord = {},
            onDelete = {},
        )
    }
}
