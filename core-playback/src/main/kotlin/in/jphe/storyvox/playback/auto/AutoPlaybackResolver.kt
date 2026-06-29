package `in`.jphe.storyvox.playback.auto

import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FollowsRepository
import `in`.jphe.storyvox.data.repository.LibraryRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Where Android Auto should start playing: a resolved (fiction, chapter, offset). */
data class PlayTarget(
    val fictionId: String,
    val chapterId: String,
    val charOffset: Int,
)

/**
 * Issue #1232 — turns an Android Auto play request (a browse media id, or a
 * voice-search query) into a concrete [PlayTarget] the playback controller can
 * start. Every dependency is a core-data repository and the return value
 * carries no Android types, so the whole resolver is JVM-unit-testable
 * (`AutoPlaybackResolverTest`).
 *
 * Voice search is **library-first**: a car request ("play Pride and Prejudice")
 * resolves against the user's already-downloaded library + follows, so playback
 * starts instantly instead of kicking off a network fetch at highway speed.
 * Searching the source backends for not-yet-added books is a sensible follow-up
 * but is deliberately out of scope here — it would pull the enabled-source
 * settings and the source registry down into core-playback.
 */
@Singleton
class AutoPlaybackResolver @Inject constructor(
    private val chapterRepo: ChapterRepository,
    private val positionRepo: PlaybackPositionRepository,
    private val libraryRepo: LibraryRepository,
    private val followsRepo: FollowsRepository,
) {
    /** Resolve a browse [mediaId] (preferred) or a voice [query] to a play
     *  target. Returns null when nothing playable matches — the caller then
     *  leaves playback untouched. */
    suspend fun resolve(mediaId: String?, query: String?): PlayTarget? {
        mediaId?.let { id ->
            when (val node = AutoMediaId.parse(id)) {
                is AutoMediaId.Node.Resume -> return mostRecent()
                is AutoMediaId.Node.Chapter -> return targetForChapter(node.fictionId, node.chapterId)
                is AutoMediaId.Node.Book -> return targetForBook(node.fictionId)
                // Root / Category / unparseable are not playable.
                else -> Unit
            }
        }
        val q = query?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // "continue" / "resume" voice phrasing → the most-recent book.
        if (q.equals("resume", ignoreCase = true) || q.contains("continue", ignoreCase = true)) {
            mostRecent()?.let { return it }
        }
        val hitId = matchLibrary(q) ?: return null
        return targetForBook(hitId)
    }

    /** The single most-recently-played (fiction, chapter, offset), or null when
     *  nothing has been played yet. Powers `/resume`, onPlaybackResumption, and
     *  "continue" voice commands. */
    suspend fun mostRecent(): PlayTarget? {
        val entry = positionRepo.observeMostRecentContinueListening().first() ?: return null
        return PlayTarget(entry.fiction.id, entry.chapter.id, entry.charOffset)
    }

    /** Resume offset for an explicit (fiction, chapter): the saved char offset
     *  when the save points at that very chapter, else 0 (start of chapter). */
    private suspend fun targetForChapter(fictionId: String, chapterId: String): PlayTarget {
        val saved = runCatching { positionRepo.load(fictionId) }.getOrNull()
        val offset = if (saved?.chapterId == chapterId) saved.charOffset else 0
        return PlayTarget(fictionId, chapterId, offset)
    }

    /** Resolve a whole book to its resume point: the saved chapter+offset if
     *  there is one, else the first chapter at offset 0. Null when the book has
     *  no chapters cached yet (nothing to play). */
    private suspend fun targetForBook(fictionId: String): PlayTarget? {
        val saved = runCatching { positionRepo.load(fictionId) }.getOrNull()
        if (saved != null) {
            return PlayTarget(fictionId, saved.chapterId, saved.charOffset)
        }
        val first = runCatching { chapterRepo.observeChapters(fictionId).first() }
            .getOrNull()
            ?.minByOrNull { it.index }
            ?: return null
        return PlayTarget(fictionId, first.id, 0)
    }

    /** Best library/follows title match for a voice [query], or null. */
    private suspend fun matchLibrary(query: String): String? {
        val library = runCatching { libraryRepo.snapshot() }.getOrDefault(emptyList())
        val follows = runCatching { followsRepo.snapshot() }.getOrDefault(emptyList())
        val candidates = library.map { it.id to it.title } + follows.map { it.id to it.title }
        return bestTitleMatch(query, candidates)
    }

    companion object {
        /** Pure title matcher, factored out for unit testing. Case-insensitive;
         *  ranks exact > prefix > substring; first candidate wins on a tie. */
        fun bestTitleMatch(query: String, candidates: List<Pair<String, String>>): String? {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return null
            var best: String? = null
            var bestRank = 0
            for ((id, title) in candidates) {
                val t = title.lowercase()
                val rank = when {
                    t == q -> 3
                    t.startsWith(q) -> 2
                    t.contains(q) -> 1
                    else -> 0
                }
                if (rank > bestRank) {
                    bestRank = rank
                    best = id
                }
            }
            return best
        }
    }
}
