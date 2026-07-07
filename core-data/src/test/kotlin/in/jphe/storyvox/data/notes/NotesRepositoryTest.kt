package `in`.jphe.storyvox.data.notes

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Voice Notes (#1657, Phase 1) — the two file-lifecycle guarantees, tested
 * plain-JVM with a fake [NoteDao] + a real temp `recordings/` dir (no Room,
 * no Robolectric): **delete-note-deletes-audio** and the **orphan-audio sweep**.
 */
class NotesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(dao: NoteDao, dir: File) = NotesRepository(dao, dir)

    private fun note(id: String, audioPath: String? = null, title: String = "") =
        NoteEntity(id = id, title = title, createdAt = 0L, updatedAt = 0L, audioPath = audioPath)

    @Test
    fun delete_removesRowAndAudioFile() = runTest {
        val dir = tmp.newFolder("recordings")
        val audio = File(dir, "n1.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val dao = FakeNoteDao()
        dao.upsert(note("n1", audioPath = audio.absolutePath))

        repo(dao, dir).delete("n1")

        assertNull("row gone", dao.get("n1"))
        assertFalse("audio file deleted with the row", audio.exists())
    }

    @Test
    fun delete_absentId_isNoOp() = runTest {
        val dir = tmp.newFolder("recordings")
        repo(FakeNoteDao(), dir).delete("nope") // must not throw
    }

    @Test
    fun delete_typedNoteWithNoAudio_isFine() = runTest {
        val dir = tmp.newFolder("recordings")
        val dao = FakeNoteDao()
        dao.upsert(note("n1", audioPath = null))

        repo(dao, dir).delete("n1")

        assertNull(dao.get("n1"))
    }

    @Test
    fun sweepOrphanAudio_deletesUnreferenced_keepsReferenced() = runTest {
        val dir = tmp.newFolder("recordings")
        val referenced = File(dir, "keep.m4a").apply { writeBytes(byteArrayOf(1)) }
        val orphan = File(dir, "orphan.m4a").apply { writeBytes(byteArrayOf(2)) }
        val dao = FakeNoteDao()
        dao.upsert(note("keep", audioPath = referenced.absolutePath))

        val reclaimed = repo(dao, dir).sweepOrphanAudio()

        assertEquals(1, reclaimed)
        assertTrue("referenced file kept", referenced.exists())
        assertFalse("orphan file swept", orphan.exists())
    }

    @Test
    fun sweepOrphanAudio_emptyDir_isZero() = runTest {
        val dir = tmp.newFolder("recordings")
        assertEquals(0, repo(FakeNoteDao(), dir).sweepOrphanAudio())
    }

    @Test
    fun search_delegatesToDao() = runTest {
        val dir = tmp.newFolder("recordings")
        val dao = FakeNoteDao()
        dao.upsert(note("n1", title = "hello world"))

        assertEquals(listOf("n1"), repo(dao, dir).search("hello").first().map { it.id })
    }

    /**
     * Plain-JVM fake — in-memory list; `search` does a case-sensitive substring
     * over title/body/transcript, newest-first (mirrors the DAO's `LIKE` shape).
     */
    private class FakeNoteDao : NoteDao {
        private val rows = MutableStateFlow<List<NoteEntity>>(emptyList())
        override suspend fun upsert(note: NoteEntity) {
            rows.value = rows.value.filterNot { it.id == note.id } + note
        }
        override fun observeAll(): Flow<List<NoteEntity>> = rows
        override suspend fun get(id: String): NoteEntity? = rows.value.find { it.id == id }
        override suspend fun all(): List<NoteEntity> = rows.value
        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        override fun search(query: String): Flow<List<NoteEntity>> = MutableStateFlow(
            rows.value.filter {
                it.title.contains(query) ||
                    (it.body?.contains(query) ?: false) ||
                    (it.transcript?.contains(query) ?: false)
            }.sortedByDescending { it.updatedAt },
        )
    }
}
