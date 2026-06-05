package `in`.jphe.storyvox.playback.audiobook

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.source.audiobook.writer.Chapterizer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a blob of pasted / typed / imported text into a local fiction with
 * chapters, ready to play in Candela and to export as an audiobook (issue
 * #1003 — "Make your own audiobook").
 *
 * The text is auto-chapterized by [Chapterizer]; each resulting section
 * becomes a `DOWNLOADED` chapter with its `plainBody` set, so it's immediately
 * narratable (the export reads `plainBody`) and readable without any network
 * round-trip. The fiction is added to the library so the user can find it
 * again. The synthetic source id is [SourceIds.MY_AUDIOBOOK].
 */
@Singleton
class CreateAudiobookFromTextUseCase @Inject constructor(
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
) {

    /**
     * Create the fiction and return its id. [text] is the raw content;
     * [title] / [author] are the user-supplied metadata (title defaults to a
     * generic label when blank).
     *
     * @throws IllegalArgumentException when [text] is blank.
     */
    suspend fun create(
        text: String,
        title: String,
        author: String,
    ): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "Can't create an audiobook from empty text" }

        val cleanTitle = title.trim().ifBlank { "My Audiobook" }
        val cleanAuthor = author.trim().ifBlank { "Me" }
        val fictionId = "${SourceIds.MY_AUDIOBOOK}:${UUID.randomUUID()}"
        val now = System.currentTimeMillis()

        val sections = Chapterizer.chapterize(text, cleanTitle)
            .ifEmpty { return@withContext fictionId } // blank guarded above; defensive

        val fiction = Fiction(
            id = fictionId,
            sourceId = SourceIds.MY_AUDIOBOOK,
            title = cleanTitle,
            author = cleanAuthor,
            status = FictionStatus.COMPLETED,
            chapterCount = sections.size,
            firstSeenAt = now,
            metadataFetchedAt = now,
            inLibrary = true,
            addedToLibraryAt = now,
        )
        fictionDao.upsert(fiction)

        val chapters = sections.mapIndexed { index, section ->
            Chapter(
                // Chapter PK convention is "$fictionId:$index" (see Chapter.kt).
                id = "$fictionId:$index",
                fictionId = fictionId,
                sourceChapterId = index.toString(),
                index = index,
                title = section.title,
                plainBody = section.text,
                wordCount = section.text.split(Regex("\\s+")).count { it.isNotBlank() },
                bodyFetchedAt = now,
                downloadState = ChapterDownloadState.DOWNLOADED,
            )
        }
        chapterDao.upsertAll(chapters)

        fictionId
    }
}
