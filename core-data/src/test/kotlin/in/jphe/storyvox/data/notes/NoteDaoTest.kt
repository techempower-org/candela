package `in`.jphe.storyvox.data.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Voice Notes (#1657, Phase 1) — in-memory Room exercise of [NoteDao] against
 * the separate [NotesDatabase]. Mirrors `AnnotationDaoTest`'s posture.
 * Covers: upsert/get roundtrip incl. the [TranscriptionStatus] enum converter,
 * REPLACE-on-id, `observeAll` ordering, delete, `LIKE` search across
 * title/body/transcript (+ empty-query-matches-all), and nullable-field roundtrip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NoteDaoTest {

    private lateinit var db: NotesDatabase
    private lateinit var dao: NoteDao

    private fun note(
        id: String,
        title: String = "",
        body: String? = null,
        transcript: String? = null,
        tags: String = "",
        status: TranscriptionStatus = TranscriptionStatus.NONE,
        audioPath: String? = null,
        at: Long = 1000L,
    ) = NoteEntity(
        id = id, title = title, createdAt = at, updatedAt = at, tags = tags,
        audioPath = audioPath, transcript = transcript, body = body, transcriptionStatus = status,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsert_thenGet_roundTripsIncludingEnum() = runTest {
        dao.upsert(note("n1", title = "Groceries", body = "milk", status = TranscriptionStatus.DONE))

        val got = dao.get("n1")!!
        assertEquals("Groceries", got.title)
        assertEquals("milk", got.body)
        assertEquals(TranscriptionStatus.DONE, got.transcriptionStatus)
    }

    @Test
    fun upsertSameId_replacesInPlace() = runTest {
        dao.upsert(note("n1", title = "draft", at = 1000L))
        dao.upsert(note("n1", title = "final", at = 2000L))

        assertEquals("exactly one row", 1, dao.all().size)
        assertEquals("final", dao.get("n1")!!.title)
    }

    @Test
    fun observeAll_ordersByUpdatedAtDesc() = runTest {
        dao.upsert(note("old", at = 1000L))
        dao.upsert(note("new", at = 3000L))
        dao.upsert(note("mid", at = 2000L))

        assertEquals(listOf("new", "mid", "old"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun delete_removesOnlyThatRow() = runTest {
        dao.upsert(note("a"))
        dao.upsert(note("b"))

        dao.delete("a")

        assertEquals(listOf("b"), dao.all().map { it.id })
    }

    @Test
    fun search_matchesTitleBodyTranscript_andEmptyMatchesAll() = runTest {
        dao.upsert(note("t", title = "Standup meeting", at = 3000L))
        dao.upsert(note("b", body = "buy oat milk", at = 2000L))
        dao.upsert(note("x", transcript = "the quarterly numbers", at = 1000L))

        assertEquals(listOf("t"), dao.search("meeting").first().map { it.id })
        assertEquals(listOf("b"), dao.search("milk").first().map { it.id })
        assertEquals(listOf("x"), dao.search("quarterly").first().map { it.id })
        // Empty query degenerates to match-all, newest-first.
        assertEquals(listOf("t", "b", "x"), dao.search("").first().map { it.id })
    }

    @Test
    fun nullableFields_roundTrip() = runTest {
        dao.upsert(note("n1")) // audio/body/transcript/summary/duration all null

        val got = dao.get("n1")!!
        assertNull(got.audioPath)
        assertNull(got.durationMs)
        assertNull(got.transcript)
        assertNull(got.summary)
        assertNull(got.body)
        assertEquals(TranscriptionStatus.NONE, got.transcriptionStatus)
    }
}
