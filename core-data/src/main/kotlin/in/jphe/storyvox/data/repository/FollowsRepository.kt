package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.UnreadChapterRow
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.repository.playback.FollowItem
import `in`.jphe.storyvox.data.repository.playback.UnreadChapter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only "Follows" accessor used by the playback layer. `snapshot()` lists
 * fictions the user follows on the source (mirrored from Royal Road's own
 * Follows list); `unreadChapters()` is the across-all-followed feed used to
 * power Auto's "Continue subscribed series" rail.
 *
 * Toggling a follow lives on [FictionRepository.setFollowedRemote] — that's
 * the writeable side and goes through the source.
 */
interface FollowsRepository {
    suspend fun snapshot(): List<FollowItem>
    suspend fun unreadChapters(limit: Int): List<UnreadChapter>
}

@Singleton
class FollowsRepositoryImpl @Inject constructor(
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
) : FollowsRepository {

    override suspend fun snapshot(): List<FollowItem> =
        fictionDao.followsSnapshot().map(Fiction::toFollowItem)

    override suspend fun unreadChapters(limit: Int): List<UnreadChapter> =
        chapterDao.unreadChapters(limit).map(UnreadChapterRow::toUnreadChapter)
}

private fun Fiction.toFollowItem(): FollowItem = FollowItem(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
)

private fun UnreadChapterRow.toUnreadChapter(): UnreadChapter = UnreadChapter(
    fictionId = fictionId,
    chapterId = chapterId,
    bookTitle = bookTitle,
    chapterTitle = chapterTitle,
    coverUrl = coverUrl,
    bodyPreview = bodyPreview,
)
