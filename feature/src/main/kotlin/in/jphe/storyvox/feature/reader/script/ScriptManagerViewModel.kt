package `in`.jphe.storyvox.feature.reader.script

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.db.dao.TeleprompterScriptDao
import `in`.jphe.storyvox.data.db.entity.ScriptFormat
import `in`.jphe.storyvox.data.db.entity.TeleprompterScript
import `in`.jphe.storyvox.playback.PendingTeleprompterScript
import `in`.jphe.storyvox.playback.TeleprompterController
import `in`.jphe.storyvox.playback.TeleprompterScriptStore
import java.util.UUID
import javax.inject.Inject
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
 * Issue #1369 — ViewModel for the script **list** screen ([ScriptListScreen]).
 *
 * Owns the search box state, the observed feed, swipe-to-delete-with-undo,
 * duplicate, and "Load into Teleprompter" (writes the shared
 * [TeleprompterScriptStore]; the screen performs the actual navigation).
 *
 * The edit screen uses the separate [ScriptEditViewModel] below — it needs a
 * per-route `scriptId` from [SavedStateHandle], which a list-route VM instance
 * wouldn't have.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScriptManagerViewModel @Inject constructor(
    private val dao: TeleprompterScriptDao,
    private val store: TeleprompterScriptStore,
    private val teleprompter: TeleprompterController,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    /** Live search text bound to the list screen's search bar. */
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The script feed for the current [query]. `search("")` matches every row
     * (`LIKE '%%'`), so binding the search flow for both the empty and
     * non-empty query states keeps a single source of truth.
     */
    val scripts: StateFlow<List<TeleprompterScript>> =
        _query
            .flatMapLatest { q -> dao.search(q.trim()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The most-recently-deleted script, retained so the undo snackbar can
     *  restore it. Cleared once a new delete supersedes it. */
    private var lastDeleted: TeleprompterScript? = null

    fun onQueryChange(value: String) { _query.value = value }

    /**
     * Delete [script], remembering it so [undoDelete] can put it back. The
     * screen pairs this with an "Undo" snackbar (#1369 swipe-to-delete).
     */
    fun deleteWithUndo(script: TeleprompterScript) {
        lastDeleted = script
        viewModelScope.launch { dao.delete(script.id) }
    }

    /** Restore the last [deleteWithUndo]'d script (re-inserts the same row). */
    fun undoDelete() {
        val restore = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch { dao.upsert(restore) }
    }

    /** Long-press → Duplicate: a fresh row with a new id, a "(copy)" title,
     *  and refreshed timestamps; sorts to the top of the feed. */
    fun duplicate(script: TeleprompterScript) {
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            dao.upsert(
                script.copy(
                    id = UUID.randomUUID().toString(),
                    title = duplicateTitle(script.title),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    /**
     * Queue [script] for the teleprompter and flip the transport on (the
     * screen then navigates to Playing). Mirrors #1366's producer-half: the
     * reader/#1368 consumer renders the parked script as the scroll content.
     */
    fun loadIntoTeleprompter(script: TeleprompterScript) {
        store.load(
            PendingTeleprompterScript(id = script.id, title = script.title, body = script.body),
        )
        teleprompter.setEnabled(true)
    }
}

/** "Talk" → "Talk (copy)"; blank/untitled → "Untitled (copy)". */
internal fun duplicateTitle(title: String): String =
    (title.ifBlank { "Untitled" }) + " (copy)"

@Immutable
data class ScriptEditUiState(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val tags: String = "",
    /** [ScriptFormat] name — Freeform / YouTube Short / Full Show. */
    val format: String = ScriptFormat.FREEFORM.name,
    /** True until the first save — drives the top-bar title ("New script" vs
     *  "Edit script") and whether Back warns about unsaved changes. */
    val isNewDraft: Boolean = true,
    /** True once the current in-memory content differs from what's persisted
     *  (or, for a new draft, once anything has been typed). Gates the Save
     *  button's enabled state. */
    val isDirty: Boolean = false,
)

/** One-shot events from the editor to its screen. */
sealed interface ScriptEditEvent {
    /** A save completed — the screen shows a confirmation snackbar. */
    data object Saved : ScriptEditEvent
}

/**
 * Issue #1369 — ViewModel for the script **edit** screen ([ScriptEditScreen]).
 *
 * Loads the script named by the `scriptId` route arg (or starts a blank draft
 * with that id when the row doesn't exist yet — the list FAB navigates with a
 * freshly-minted UUID, so the first [save] inserts it). Exposes the live word
 * count + duration (computed in the composable from the pure
 * [TeleprompterScript] helpers and the current [wpm]), clipboard import, save,
 * and "Load into Teleprompter".
 */
@HiltViewModel
class ScriptEditViewModel @Inject constructor(
    private val dao: TeleprompterScriptDao,
    private val store: TeleprompterScriptStore,
    private val teleprompterController: TeleprompterController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val scriptId: String = checkNotNull(savedStateHandle["scriptId"]) {
        "ScriptEditViewModel requires a scriptId route arg"
    }

    private val _uiState = MutableStateFlow(ScriptEditUiState(id = scriptId))
    val uiState: StateFlow<ScriptEditUiState> = _uiState.asStateFlow()

    /** The live teleprompter pace, so the editor's duration figure tracks the
     *  speed the user will actually read at (distinct from the row badge's
     *  fixed [TeleprompterScript.WPM_BASELINE]). */
    val wpm: StateFlow<Int> = teleprompterController.wpm

    private val _events = Channel<ScriptEditEvent>(Channel.BUFFERED)
    val events: Flow<ScriptEditEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val existing = dao.get(scriptId)
            if (existing != null) {
                _uiState.value = ScriptEditUiState(
                    id = existing.id,
                    title = existing.title,
                    body = existing.body,
                    tags = existing.tags,
                    format = existing.format,
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

    fun onFormatChange(format: ScriptFormat) {
        _uiState.value = _uiState.value.copy(format = format.name, isDirty = true)
    }

    /** Import-from-clipboard: append the pasted [text] to the body (the screen
     *  reads the clipboard; the VM stays Android-free). No-op on blank text. */
    fun appendToBody(text: String) {
        if (text.isBlank()) return
        val current = _uiState.value.body
        val joined = if (current.isEmpty()) text else "$current\n$text"
        onBodyChange(joined)
    }

    /** Persist the current content. Recomputes the stored duration estimate at
     *  the canonical baseline. Emits [ScriptEditEvent.Saved] when done. */
    fun save() {
        val state = _uiState.value
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            val existing = dao.get(state.id)
            val createdAt = existing?.createdAt ?: now
            dao.upsert(
                TeleprompterScript(
                    id = state.id,
                    title = state.title.trim(),
                    body = state.body,
                    estimatedDurationSecs = TeleprompterScript.estimateDurationSecs(state.body),
                    tags = normalizeTags(state.tags),
                    format = state.format,
                    createdAt = createdAt,
                    updatedAt = now,
                ),
            )
            _uiState.value = state.copy(isNewDraft = false, isDirty = false)
            _events.send(ScriptEditEvent.Saved)
        }
    }

    /** Queue the current (possibly unsaved) editor content for the
     *  teleprompter and flip the transport on. The screen navigates to Playing
     *  afterward; the reader/#1368 consumer renders the parked script. */
    fun loadIntoTeleprompter() {
        val state = _uiState.value
        store.load(
            PendingTeleprompterScript(id = state.id, title = state.title, body = state.body),
        )
        teleprompterController.setEnabled(true)
    }
}

/**
 * Normalize a comma-separated tag string: trim each tag, drop blanks and
 * case-insensitive duplicates, re-join with ", ". Keeps stored tags tidy so
 * the chip display and the `tags LIKE` search behave predictably.
 */
internal fun normalizeTags(raw: String): String =
    raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .joinToString(", ")
