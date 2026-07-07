package `in`.jphe.storyvox.feature.notes.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.NotesRepository
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Voice Notes (epic #1657, Phase 4) — ViewModel for the notes **list**
 * ([NotesListScreen]). Mirrors [ScriptManagerViewModel][`in`.jphe.storyvox.feature.reader.script.ScriptManagerViewModel]:
 * a search box bound to a `flatMapLatest` feed over [NotesRepository.search]
 * (which is `LIKE`-based over title / body / transcript), plus delete.
 *
 * **Delete has no undo — by design.** [NotesRepository.delete] removes the row
 * AND the backing `.m4a` recording (spec §3.4/§3.7 — no dangling audio). Unlike
 * the Scripts list (pure text, so a swipe-undo just re-inserts the row), a note
 * delete is destructive to an irreplaceable recording, so the screen guards it
 * behind a confirm dialog instead of a swipe-to-dismiss + "Undo" snackbar. A
 * true undo would need a Phase-1 soft-delete API that doesn't exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val repo: NotesRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    /** Live search text bound to the list screen's search bar. */
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The note feed for the current [query]. `search("")` matches every row
     * (`LIKE '%%'`, ordered by `updatedAt DESC`), so one flow drives both the
     * empty and non-empty query states — a single source of truth.
     */
    val notes: StateFlow<List<NoteEntity>> =
        _query
            .flatMapLatest { q -> repo.search(q.trim()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { _query.value = value }

    /** Delete a note and its recording (see class KDoc — intentionally no undo). */
    fun delete(note: NoteEntity) {
        viewModelScope.launch { repo.delete(note.id) }
    }
}

/**
 * Immutable snapshot of the note **detail / editor** ([NoteDetailScreen]).
 *
 * A note is one row that can be a typed note, a recording-backed note, or both:
 * [transcript] / [summary] / [audioPath] / [durationMs] are the recording +
 * pipeline outputs (populated by Phases 2/3), while [title] / [body] / [tags]
 * are the user-editable fields. [transcript] is shown read-only — it's the
 * immutable ASR source of truth; the user edits [body], not the transcript.
 */
@Immutable
data class NoteDetailUiState(
    val id: String = "",
    val title: String = "",
    /** User-editable body (the [NoteEntity.body] column). */
    val body: String = "",
    val tags: String = "",
    /** Read-only ASR transcript ([NoteEntity.transcript]); null until Phase 2b runs. */
    val transcript: String? = null,
    val transcriptLang: String? = null,
    /** AI summary ([NoteEntity.summary]); null until the user consents (Phase 3). */
    val summary: String? = null,
    /** Recording path ([NoteEntity.audioPath]); null for a typed-only note. */
    val audioPath: String? = null,
    val durationMs: Long? = null,
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.NONE,
    /** True until the first save — drives the top-bar title + unsaved-changes cue. */
    val isNewDraft: Boolean = true,
    /** True once the in-memory content diverges from what's persisted. Gates Save. */
    val isDirty: Boolean = false,
)

/** One-shot events from the detail VM to its screen. */
sealed interface NoteDetailEvent {
    /** A save completed — the screen confirms with a snackbar. */
    data object Saved : NoteDetailEvent
    /** The note was deleted — the screen pops back to the list. */
    data object Deleted : NoteDetailEvent
    /** Share the assembled note text — the screen fires an ACTION_SEND chooser. */
    data class Share(val text: String) : NoteDetailEvent
    /** Summarize was tapped before Phase 3 landed — the screen shows a notice. */
    data object SummarizeUnavailable : NoteDetailEvent
}

/**
 * Voice Notes (#1657, Phase 4) — ViewModel for the note **detail / editor**
 * ([NoteDetailScreen]). Mirrors [ScriptEditViewModel][`in`.jphe.storyvox.feature.reader.script.ScriptEditViewModel]:
 * loads the note named by the `noteId` route arg (or seeds a blank new-draft
 * with that id when no row exists yet — the list's "New note" action navigates
 * with a fresh UUID, so the first [save] inserts it). The Record flow also
 * lands here after it persists a recording-backed row.
 */
@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val repo: NotesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val noteId: String = checkNotNull(savedStateHandle[NOTE_ID_ARG]) {
        "NoteDetailViewModel requires a $NOTE_ID_ARG route arg"
    }

    /** Preserved across saves so the created-at timestamp is stable (updatedAt moves). */
    private var createdAt: Long = System.currentTimeMillis()

    private val _uiState = MutableStateFlow(NoteDetailUiState(id = noteId))
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    private val _events = Channel<NoteDetailEvent>(Channel.BUFFERED)
    val events: Flow<NoteDetailEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val existing = repo.get(noteId)
            if (existing != null) {
                createdAt = existing.createdAt
                _uiState.value = NoteDetailUiState(
                    id = existing.id,
                    title = existing.title,
                    body = existing.body.orEmpty(),
                    tags = existing.tags,
                    transcript = existing.transcript,
                    transcriptLang = existing.transcriptLang,
                    summary = existing.summary,
                    audioPath = existing.audioPath,
                    durationMs = existing.durationMs,
                    transcriptionStatus = existing.transcriptionStatus,
                    isNewDraft = false,
                    isDirty = false,
                )
            }
            // No row → keep the blank new-draft state seeded above.
        }
    }

    fun onTitleChange(value: String) {
        _uiState.value = _uiState.value.copy(title = value, isDirty = true)
    }

    fun onBodyChange(value: String) {
        _uiState.value = _uiState.value.copy(body = value, isDirty = true)
    }

    fun onTagsChange(value: String) {
        _uiState.value = _uiState.value.copy(tags = value, isDirty = true)
    }

    /**
     * Persist the current content. Preserves the recording fields (audio /
     * transcript / summary / status) untouched — the editor only owns
     * title / body / tags. Emits [NoteDetailEvent.Saved].
     */
    fun save() {
        val s = _uiState.value
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            repo.upsert(
                NoteEntity(
                    id = s.id,
                    title = s.title.trim(),
                    createdAt = createdAt,
                    updatedAt = now,
                    tags = normalizeNoteTags(s.tags),
                    audioPath = s.audioPath,
                    durationMs = s.durationMs,
                    transcript = s.transcript,
                    transcriptLang = s.transcriptLang,
                    summary = s.summary,
                    // Store an empty body as null so it doesn't shadow the
                    // transcript in the list snippet (see [noteSnippet]).
                    body = s.body.ifBlank { null },
                    transcriptionStatus = s.transcriptionStatus,
                ),
            )
            _uiState.value = s.copy(isNewDraft = false, isDirty = false)
            _events.send(NoteDetailEvent.Saved)
        }
    }

    /** Delete this note (and its recording) then pop back to the list. */
    fun delete() {
        viewModelScope.launch {
            repo.delete(_uiState.value.id)
            _events.send(NoteDetailEvent.Deleted)
        }
    }

    /** Share the note as plain text via the system chooser (title + body + transcript + summary). */
    fun export() {
        val s = _uiState.value
        val text = buildNoteExportText(s.title, s.body, s.transcript, s.summary)
        viewModelScope.launch { _events.send(NoteDetailEvent.Share(text)) }
    }

    /**
     * TODO(#1657 P3): replace with `SummarizeTranscriptUseCase` (nebula) —
     * build a prompt from the transcript, stream `LlmProvider`, and write
     * [NoteEntity.summary] behind the explicit-consent gate (spec §3.3). Until
     * Phase 3 lands, tapping Summarize surfaces a "not yet available" notice so
     * the affordance is discoverable without pretending to work.
     */
    fun summarize() {
        viewModelScope.launch { _events.send(NoteDetailEvent.SummarizeUnavailable) }
    }

    companion object {
        const val NOTE_ID_ARG: String = "noteId"
    }
}

/**
 * Normalize a comma-separated tag string: trim each, drop blanks + case-
 * insensitive duplicates, re-join with ", ". Keeps the `tags LIKE` search and
 * the chip display predictable (mirrors the Scripts editor's `normalizeTags`).
 */
internal fun normalizeNoteTags(raw: String): String =
    raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .joinToString(", ")

/**
 * A single-line snippet for the list card: the user's [NoteEntity.body] if it
 * has content, else the [NoteEntity.transcript], else empty. Newlines collapse
 * to spaces so a multi-line body doesn't blow out the card height.
 */
internal fun noteSnippet(note: NoteEntity): String {
    val source = note.body?.takeIf { it.isNotBlank() } ?: note.transcript.orEmpty()
    return source.replace(Regex("\\s+"), " ").trim()
}

/** Format a recording length in millis as `M:SS`, or `H:MM:SS` past an hour. */
internal fun formatNoteDuration(durationMs: Long): String {
    val totalSecs = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Assemble a note into shareable plain text. Title first (or "Untitled"), then
 * the user body, then the transcript, then the summary — each present section
 * separated by a blank line. Used by the detail screen's Export → share sheet.
 */
internal fun buildNoteExportText(
    title: String,
    body: String,
    transcript: String?,
    summary: String?,
): String {
    val sections = buildList {
        add(title.ifBlank { "Untitled" })
        body.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
        transcript?.takeIf { it.isNotBlank() }?.let { add("Transcript\n${it.trim()}") }
        summary?.takeIf { it.isNotBlank() }?.let { add("Summary\n${it.trim()}") }
    }
    return sections.joinToString("\n\n")
}
