package `in`.jphe.storyvox.playback.auto

/**
 * Issue #1232 — the Android Auto media-id grammar, in one pure place so the
 * browse service (which *builds* ids on every [android.support.v4.media.MediaBrowserCompat.MediaItem])
 * and the session callback (which *parses* them on play-from-media-id) can
 * never drift apart.
 *
 * Grammar — segments are percent-encoded so a fiction/chapter id may itself
 * contain `/` (some sources mint composite chapter ids):
 *
 * ```
 *   /                            root
 *   /resume                      continue the most-recently-played book
 *   /library                     category — library books   (browsable tab)
 *   /follows                     category — followed books   (browsable tab)
 *   /recent                      category — recent chapters  (browsable tab)
 *   /new                         category — unread chapters  (browsable tab)
 *   /library/<fiction>           a library book   (browsable → its chapters)
 *   /follows/<fiction>           a followed book  (browsable → its chapters)
 *   /library/<fiction>/<chapter> a chapter        (playable)
 *   /follows/<fiction>/<chapter> a chapter        (playable)
 *   /recent/<fiction>/<chapter>  a recent chapter (playable)
 *   /new/<fiction>/<chapter>     an unread chapter (playable)
 * ```
 *
 * Pure Kotlin, no Android types — covered by `AutoMediaIdTest`.
 */
object AutoMediaId {
    const val ROOT = "/"
    const val RESUME = "/resume"
    const val LIBRARY = "/library"
    const val FOLLOWS = "/follows"
    const val RECENT = "/recent"
    const val NEW = "/new"

    /** The browsable root categories Auto renders as tabs. Order is
     *  car-usefulness; Auto caps the root at 4 (see
     *  `BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`) and we already sit at 4. */
    val ROOT_CATEGORIES: List<String> = listOf(LIBRARY, FOLLOWS, RECENT, NEW)

    /** Categories whose books drill into a chapter list. */
    private val BOOK_CATEGORIES = setOf(LIBRARY, FOLLOWS)

    /** `/library/<fiction>` — a browsable book node. */
    fun book(category: String, fictionId: String): String =
        "$category/${enc(fictionId)}"

    /** `/recent/<fiction>/<chapter>` — a playable chapter node. */
    fun chapter(category: String, fictionId: String, chapterId: String): String =
        "$category/${enc(fictionId)}/${enc(chapterId)}"

    /** Parse a media id into a [Node]; null when it doesn't fit the grammar
     *  (the session callback treats null as "not ours" and ignores it). */
    fun parse(mediaId: String): Node? {
        if (mediaId == ROOT) return Node.Root
        if (mediaId == RESUME) return Node.Resume
        if (!mediaId.startsWith("/")) return null
        val parts = mediaId.removePrefix("/").split('/')
        val category = "/" + (parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null)
        if (category !in ROOT_CATEGORIES) return null
        return when (parts.size) {
            1 -> Node.Category(category)
            2 -> Node.Book(category, dec(parts[1]))
            3 -> Node.Chapter(category, dec(parts[1]), dec(parts[2]))
            else -> null
        }
    }

    /** True when [category] is a book-style category that drills to chapters. */
    fun isBookCategory(category: String): Boolean = category in BOOK_CATEGORIES

    sealed interface Node {
        data object Root : Node
        data object Resume : Node
        data class Category(val category: String) : Node
        data class Book(val category: String, val fictionId: String) : Node
        data class Chapter(
            val category: String,
            val fictionId: String,
            val chapterId: String,
        ) : Node
    }

    // Minimal reversible percent-encoding of '/' and the escape char itself.
    // Decode order matters: '%2F' before '%25' so an encoded literal "%2F"
    // round-trips (enc → "%252F", dec → "%2F").
    private fun enc(s: String): String = s.replace("%", "%25").replace("/", "%2F")
    private fun dec(s: String): String = s.replace("%2F", "/").replace("%25", "%")
}
