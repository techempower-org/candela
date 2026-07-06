package `in`.jphe.storyvox.data.notes

import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Voice Notes (#1657, Phase 1) — the single seam feature code uses for note
 * persistence. Wraps [NoteDao] with the two file-lifecycle guarantees the
 * privacy/robustness contract (spec §3.4/§3.7, §4) requires:
 *
 *  - **Deleting a note deletes its audio file** — no dangling recordings left
 *    on disk after a note is removed.
 *  - **Orphan-audio sweep** — reclaims recordings with no referencing row
 *    (e.g. a crash between writing the `.m4a` and inserting the row), so a
 *    failed capture never silently leaks storage.
 *
 * [recordingsDir] is injected (not derived from a `Context`) so this class
 * stays plain-JVM unit-testable with a temp dir + a fake DAO. Production binds
 * it to `filesDir/`[RECORDINGS_SUBDIR] — the exact path Phase 2a writes to and
 * the backup rules exclude.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val dao: NoteDao,
    @Named(RECORDINGS_DIR_QUALIFIER) private val recordingsDir: File,
) {

    fun observeAll(): Flow<List<NoteEntity>> = dao.observeAll()

    fun search(query: String): Flow<List<NoteEntity>> = dao.search(query)

    suspend fun get(id: String): NoteEntity? = dao.get(id)

    suspend fun upsert(note: NoteEntity) = dao.upsert(note)

    /**
     * Delete a note AND its recording. No-op if the id is absent. The row is
     * removed first, then the audio file — so a delete failure on the file
     * never leaves a row pointing at a deleted-then-half-gone file; a stray
     * file with no row is caught by [sweepOrphanAudio] on next launch.
     */
    suspend fun delete(id: String) {
        val note = dao.get(id) ?: return
        dao.delete(id)
        note.audioPath?.let { path ->
            runCatching { File(path).takeIf(File::exists)?.delete() }
        }
    }

    /**
     * Delete every file in [recordingsDir] that no note row references (matched
     * by file name, since audio is always `recordingsDir/<id>.m4a`). Returns the
     * number of files reclaimed. Safe to call on startup; a no-audio install is
     * a no-op (empty/absent dir → 0).
     */
    suspend fun sweepOrphanAudio(): Int {
        val referenced = dao.all()
            .mapNotNull { it.audioPath }
            .map { File(it).name }
            .toSet()
        val files = recordingsDir.listFiles()?.filter { it.isFile } ?: return 0
        var reclaimed = 0
        for (file in files) {
            if (file.name !in referenced && runCatching { file.delete() }.getOrDefault(false)) {
                reclaimed++
            }
        }
        return reclaimed
    }

    companion object {
        /**
         * Subdirectory under `filesDir` holding recordings. **Load-bearing:**
         * this exact name must match Phase 2a's write path AND the
         * `<exclude domain="file" path="recordings/">` backup rule — a mismatch
         * silently un-protects the audio (spec §3.7).
         */
        const val RECORDINGS_SUBDIR: String = "recordings"

        /** Hilt qualifier for the injected [recordingsDir] `File`. */
        const val RECORDINGS_DIR_QUALIFIER: String = "notesRecordingsDir"
    }
}
