package `in`.jphe.storyvox.feature.notes.ui

import androidx.lifecycle.SavedStateHandle
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.NotesRepository
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import `in`.jphe.storyvox.llm.feature.SummarizeTranscriptUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Voice Notes (epic #1657, Phase 4) — plain-JVM coverage for the notes VMs and
 * pure helpers, using a [FakeNoteDao] wrapped in the *real* [NotesRepository]
 * (the codebase's hand-rolled-fake posture; no Android / Room / files needed —
 * a typed note carries no audio path, so delete never touches the temp dir).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** #1657 Phase 3 — the detail VM gained a summarize seam; this suite doesn't
     *  exercise summarization, so a no-op fake satisfies the constructor
     *  (interface seam → no `LlmRepository` needed here). */
    private val fakeSummarize = object : SummarizeTranscriptUseCase {
        override fun summarize(transcript: String, transcriptLang: String?): Flow<String> = emptyFlow()
    }

    // One shared UnconfinedTestDispatcher for Main + runTest, so viewModelScope,
    // backgroundScope and the test body all run on the same eager scheduler —
    // the WhileSubscribed stateIn feeds emit deterministically for `.value` reads.
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var dao: FakeNoteDao
    private lateinit var repo: NotesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = FakeNoteDao()
        repo = NotesRepository(dao, tmp.root)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun note(
        id: String,
        title: String = "",
        body: String? = null,
        transcript: String? = null,
        updatedAt: Long = 0L,
        audioPath: String? = null,
        durationMs: Long? = null,
        status: TranscriptionStatus = TranscriptionStatus.NONE,
    ) = NoteEntity(
        id = id,
        title = title,
        createdAt = 0L,
        updatedAt = updatedAt,
        body = body,
        transcript = transcript,
        audioPath = audioPath,
        durationMs = durationMs,
        transcriptionStatus = status,
    )

    private suspend fun seed(vararg notes: NoteEntity) = notes.forEach { dao.upsert(it) }

    // ── List VM ──────────────────────────────────────────────────────────

    @Test
    fun `empty query lists all notes, newest edit first`() = runTest(dispatcher) {
        seed(note("a", updatedAt = 100), note("b", updatedAt = 300), note("c", updatedAt = 200))
        val vm = NotesListViewModel(repo)
        backgroundScope.launch { vm.notes.collect {} }
        runCurrent()

        assertEquals(listOf("b", "c", "a"), vm.notes.value.map { it.id })
    }

    @Test
    fun `search matches title, body, and transcript`() = runTest(dispatcher) {
        seed(
            note("t", title = "Groceries", updatedAt = 1),
            note("b", title = "Untitled", body = "buy milk and bread", updatedAt = 2),
            note("x", title = "Meeting", transcript = "discuss the milk budget", updatedAt = 3),
            note("n", title = "Nothing", body = "unrelated", updatedAt = 4),
        )
        val vm = NotesListViewModel(repo)
        backgroundScope.launch { vm.notes.collect {} }

        vm.onQueryChange("milk")
        runCurrent()

        assertEquals(setOf("b", "x"), vm.notes.value.map { it.id }.toSet())
    }

    @Test
    fun `delete removes the note`() = runTest(dispatcher) {
        seed(note("a", updatedAt = 1), note("b", updatedAt = 2))
        val vm = NotesListViewModel(repo)
        backgroundScope.launch { vm.notes.collect {} }
        runCurrent()

        vm.delete(note("a", updatedAt = 1))
        runCurrent()

        assertEquals(listOf("b"), vm.notes.value.map { it.id })
    }

    // ── Detail VM ────────────────────────────────────────────────────────

    @Test
    fun `detail loads an existing note into state`() = runTest(dispatcher) {
        seed(
            note(
                "n1",
                title = "Kept",
                body = "body text",
                transcript = "spoken words",
                audioPath = "/x/n1.m4a",
                durationMs = 5_000,
                status = TranscriptionStatus.DONE,
                updatedAt = 9,
            ),
        )
        val vm = NoteDetailViewModel(repo, fakeSummarize, SavedStateHandle(mapOf("noteId" to "n1")))
        runCurrent()

        val s = vm.uiState.value
        assertFalse("existing note is not a new draft", s.isNewDraft)
        assertEquals("Kept", s.title)
        assertEquals("body text", s.body)
        assertEquals("spoken words", s.transcript)
        assertEquals("/x/n1.m4a", s.audioPath)
        assertEquals(TranscriptionStatus.DONE, s.transcriptionStatus)
    }

    @Test
    fun `detail save inserts a new typed note with normalized tags`() = runTest(dispatcher) {
        val vm = NoteDetailViewModel(repo, fakeSummarize, SavedStateHandle(mapOf("noteId" to "new1")))
        runCurrent()
        assertTrue("absent row seeds a new draft", vm.uiState.value.isNewDraft)

        vm.onTitleChange("Fresh")
        vm.onBodyChange("hello")
        vm.onTagsChange("a, A , b")
        vm.save()
        runCurrent()

        val stored = dao.get("new1")
        assertNotNull(stored)
        assertEquals("Fresh", stored!!.title)
        assertEquals("hello", stored.body)
        assertEquals("a, b", stored.tags)
        assertFalse(vm.uiState.value.isNewDraft)
        assertFalse(vm.uiState.value.isDirty)
    }

    @Test
    fun `detail save preserves the recording fields and createdAt`() = runTest(dispatcher) {
        seed(
            NoteEntity(
                id = "rec",
                title = "",
                createdAt = 111L,
                updatedAt = 222L,
                audioPath = "/x/rec.m4a",
                durationMs = 8_000,
                transcript = "hi there",
                transcriptLang = "en",
                transcriptionStatus = TranscriptionStatus.DONE,
            ),
        )
        val vm = NoteDetailViewModel(repo, fakeSummarize, SavedStateHandle(mapOf("noteId" to "rec")))
        runCurrent()

        vm.onTitleChange("Titled now")
        vm.save()
        runCurrent()

        val stored = dao.get("rec")!!
        assertEquals("Titled now", stored.title)
        assertEquals("createdAt preserved", 111L, stored.createdAt)
        assertEquals("audio preserved", "/x/rec.m4a", stored.audioPath)
        assertEquals(8_000L, stored.durationMs)
        assertEquals("immutable transcript preserved", "hi there", stored.transcript)
        assertEquals(TranscriptionStatus.DONE, stored.transcriptionStatus)
        assertTrue("updatedAt bumped", stored.updatedAt > 222L)
    }

    @Test
    fun `detail save stores a blank body as null so it doesn't shadow the transcript`() = runTest(dispatcher) {
        seed(note("only-audio", transcript = "the spoken content", durationMs = 3_000, status = TranscriptionStatus.DONE))
        val vm = NoteDetailViewModel(repo, fakeSummarize, SavedStateHandle(mapOf("noteId" to "only-audio")))
        runCurrent()

        // User opens the note and saves without typing a body.
        vm.onTitleChange("A title")
        vm.save()
        runCurrent()

        val stored = dao.get("only-audio")!!
        assertNull("blank body persists as null", stored.body)
        assertEquals("the spoken content", noteSnippet(stored))
    }

    // ── Pure helpers ─────────────────────────────────────────────────────

    @Test
    fun `noteSnippet prefers body, falls back to transcript, collapses whitespace`() {
        assertEquals("hello world", noteSnippet(note("x", body = "hello\n  world", transcript = "T")))
        assertEquals("transcribed", noteSnippet(note("x", body = "   ", transcript = "transcribed")))
        assertEquals("", noteSnippet(note("x")))
    }

    @Test
    fun `formatNoteDuration renders MSS and HMMSS`() {
        assertEquals("0:14", formatNoteDuration(14_000))
        assertEquals("1:32", formatNoteDuration(92_000))
        assertEquals("1:01:01", formatNoteDuration(3_661_000))
        assertEquals("0:00", formatNoteDuration(-5))
    }

    @Test
    fun `normalizeNoteTags trims, dedupes case-insensitively, and rejoins`() {
        assertEquals("a, b, c", normalizeNoteTags("a, A , b,, c"))
        assertEquals("", normalizeNoteTags("  ,  "))
    }

    @Test
    fun `buildNoteExportText assembles present sections`() {
        assertEquals(
            "T\n\nB\n\nTranscript\nX\n\nSummary\nY",
            buildNoteExportText("T", "B", "X", "Y"),
        )
        assertEquals("Untitled", buildNoteExportText("", "", null, null))
        assertEquals("Just a title", buildNoteExportText("Just a title", "  ", null, ""))
    }

    @Test
    fun `transcriptionStatusLabel shows only in-flight and failed states`() {
        assertEquals("Pending", transcriptionStatusLabel(TranscriptionStatus.PENDING))
        assertEquals("Transcribing…", transcriptionStatusLabel(TranscriptionStatus.RUNNING))
        assertEquals("Failed", transcriptionStatusLabel(TranscriptionStatus.FAILED))
        assertNull(transcriptionStatusLabel(TranscriptionStatus.NONE))
        assertNull(transcriptionStatusLabel(TranscriptionStatus.DONE))
    }
}
