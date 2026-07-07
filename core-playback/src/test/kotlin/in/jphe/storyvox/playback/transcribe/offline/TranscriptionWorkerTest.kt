package `in`.jphe.storyvox.playback.transcribe.offline

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import `in`.jphe.storyvox.data.notes.NoteDao
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.NotesRepository
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Voice Notes (#1657, Phase 2b) — [TranscriptionWorker] status transitions via
 * the WorkManager test harness, with a fake [OfflineTranscriber] and a
 * fake-DAO-backed real [NotesRepository] (no Room, no sherpa). Covers the
 * PENDING→RUNNING→DONE happy path, model-not-ready (stays PENDING), decode
 * failure (FAILED + retry), and the typed-note no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TranscriptionWorkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var notes: NotesRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notes = NotesRepository(FakeNoteDao(), tmp.newFolder("recordings"))
    }

    private fun worker(transcriber: OfflineTranscriber): TranscriptionWorker =
        TestListenableWorkerBuilder<TranscriptionWorker>(context)
            .setInputData(workDataOf(TranscriptionWorker.KEY_NOTE_ID to NOTE_ID))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = TranscriptionWorker(appContext, workerParameters, notes, transcriber)
            })
            .build()

    @Test
    fun modelNotReady_leavesNotePending_withNoTranscript() = runTest {
        notes.upsert(note(TranscriptionStatus.PENDING, audioPath = "/x.m4a"))

        val result = worker(FakeTranscriber(ready = false)).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val n = notes.get(NOTE_ID)!!
        assertEquals(TranscriptionStatus.PENDING, n.transcriptionStatus)
        assertNull(n.transcript)
    }

    @Test
    fun happyPath_streamsJoinedTranscript_andMarksDone() = runTest {
        notes.upsert(note(TranscriptionStatus.PENDING, audioPath = "/x.m4a"))

        val result = worker(
            FakeTranscriber(ready = true, segments = listOf(seg("Hello."), seg("World."))),
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val n = notes.get(NOTE_ID)!!
        assertEquals(TranscriptionStatus.DONE, n.transcriptionStatus)
        assertEquals("Hello. World.", n.transcript)
    }

    @Test
    fun decodeFailure_marksFailed_andRetriesWhileUnderCap() = runTest {
        notes.upsert(note(TranscriptionStatus.PENDING, audioPath = "/x.m4a"))

        val result = worker(
            FakeTranscriber(ready = true, error = RuntimeException("decode boom")),
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(TranscriptionStatus.FAILED, notes.get(NOTE_ID)!!.transcriptionStatus)
    }

    @Test
    fun typedNoteWithNoAudio_isNoOp() = runTest {
        notes.upsert(note(TranscriptionStatus.NONE, audioPath = null))

        val result = worker(FakeTranscriber(ready = true)).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(TranscriptionStatus.NONE, notes.get(NOTE_ID)!!.transcriptionStatus)
    }

    // ── Fakes / helpers ─────────────────────────────────────────────────

    private fun note(status: TranscriptionStatus, audioPath: String?) = NoteEntity(
        id = NOTE_ID, createdAt = 0L, updatedAt = 0L, audioPath = audioPath, transcriptionStatus = status,
    )

    private fun seg(text: String) = TranscriptionSegment(startMs = 0L, endMs = 1000L, text = text)

    private class FakeTranscriber(
        private val ready: Boolean,
        private val segments: List<TranscriptionSegment> = emptyList(),
        private val error: Throwable? = null,
    ) : OfflineTranscriber {
        override fun isModelReady(): Boolean = ready
        override fun transcribe(audioPath: String, languageHint: String?): Flow<TranscriptionSegment> =
            if (error != null) flow { throw error } else segments.asFlow()
    }

    private class FakeNoteDao : NoteDao {
        private val rows = MutableStateFlow<List<NoteEntity>>(emptyList())
        override suspend fun upsert(note: NoteEntity) {
            rows.value = rows.value.filterNot { it.id == note.id } + note
        }
        override fun observeAll(): Flow<List<NoteEntity>> = rows
        override suspend fun get(id: String): NoteEntity? = rows.value.find { it.id == id }
        override suspend fun all(): List<NoteEntity> = rows.value
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        override fun search(query: String): Flow<List<NoteEntity>> = rows
        override suspend fun updateTranscription(id: String, transcript: String?, status: TranscriptionStatus, updatedAt: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(transcript = transcript, transcriptionStatus = status, updatedAt = updatedAt) else it }
        }
        override suspend fun updateTranscriptionStatus(id: String, status: TranscriptionStatus, updatedAt: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(transcriptionStatus = status, updatedAt = updatedAt) else it }
        }
        override suspend fun updateEdit(id: String, title: String, body: String?, tags: String, updatedAt: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(title = title, body = body, tags = tags, updatedAt = updatedAt) else it }
        }
    }

    companion object {
        private const val NOTE_ID = "n1"
    }
}
