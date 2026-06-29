package `in`.jphe.storyvox.llm.tools

/**
 * Issue #216 — the v1 catalog of AI-callable storyvox actions.
 *
 * Each [ToolSpec] is paired with a [ToolHandler] in
 * `:feature/chat/tools/ChatToolHandlers.kt` at the app DI layer. The
 * specs live here because (a) tests assert against them, (b) the
 * provider classes serialize them into the wire request, and (c)
 * `:feature/chat` already depends on `:core-llm` so the import path
 * is downstream-only.
 *
 * Adding a tool:
 *  1. Define a new [ToolSpec] here.
 *  2. Add the matching [ToolHandler] in `ChatToolHandlers`.
 *  3. Add a contract test in `:core-llm` and a behaviour test in
 *     `:feature/chat`.
 *
 * Descriptions are written in present-tense imperative ("Move a
 * fiction onto…") because that's the voice the model seems to weigh
 * most heavily when picking between tools — verified empirically
 * against Claude Haiku 4.5 + GPT-4o-mini.
 */
object StoryvoxToolSpecs {

    /** Tool name constants — re-exported so handler bindings and
     *  tests can refer to them symbolically rather than re-typing
     *  the snake_case strings. */
    const val ADD_TO_SHELF = "add_to_shelf"
    const val QUEUE_CHAPTER = "queue_chapter"
    const val MARK_CHAPTER_READ = "mark_chapter_read"
    const val SET_SPEED = "set_speed"
    const val OPEN_VOICE_LIBRARY = "open_voice_library"

    /** Issue #1227 — catalog-discovery tools. Read-only: they give the
     *  model eyes on the source backends so it can answer "find me a
     *  book about…" / "what should I read next" prompts with real
     *  titles + ids instead of hallucinated ones. */
    const val SEARCH_SOURCES = "search_sources"
    const val GET_BOOK_DETAILS = "get_book_details"

    /** Allowed values for the `shelf` parameter of [ADD_TO_SHELF]. */
    val SHELVES: List<String> = listOf("Reading", "Read", "Wishlist")

    /** Minimum / maximum playback speed accepted by [SET_SPEED]. The
     *  player itself enforces the same range; this is the same
     *  range the user sees on the playback speed slider. */
    const val SPEED_MIN: Float = 0.5f
    const val SPEED_MAX: Float = 2.5f

    /** Issue #1227 — default + ceiling for [SEARCH_SOURCES]'s `limit`.
     *  The default keeps the aggregated tool-result blob small enough
     *  to sit comfortably in the model's context window; the ceiling
     *  caps a greedy `limit` before we render the rows. The handler
     *  coerces into `1..SEARCH_LIMIT_MAX`. */
    const val SEARCH_LIMIT_DEFAULT: Int = 8
    const val SEARCH_LIMIT_MAX: Int = 25

    val addToShelf: ToolSpec = ToolSpec(
        name = ADD_TO_SHELF,
        description = "Move a fiction onto one of the user's three " +
            "library shelves: Reading, Read, or Wishlist. Use this " +
            "when the user asks to 'add this book to my Reading " +
            "shelf', 'mark this as read', or similar. The active " +
            "fiction's id is in the chat context — pass it unless " +
            "the user explicitly names a different book.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "fictionId",
                description = "The stable id of the fiction to shelve. " +
                    "Use the active fiction's id from the chat context.",
            ),
            ToolParameter.StringParam(
                name = "shelf",
                description = "Which shelf to move the fiction onto.",
                allowedValues = SHELVES,
            ),
        ),
    )

    val queueChapter: ToolSpec = ToolSpec(
        name = QUEUE_CHAPTER,
        description = "Start playback of a specific chapter. Use this " +
            "when the user asks to 'play chapter N', 'queue the next " +
            "chapter', or similar navigation. The chapter index is " +
            "zero-based — chapter 1 is index 0, chapter 5 is index 4.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "fictionId",
                description = "The stable id of the fiction whose " +
                    "chapter to play. Use the active fiction's id " +
                    "from the chat context.",
            ),
            ToolParameter.IntParam(
                name = "chapterIndex",
                description = "Zero-based chapter index in reading " +
                    "order. Chapter 1 is index 0.",
                min = 0,
            ),
        ),
    )

    val markChapterRead: ToolSpec = ToolSpec(
        name = MARK_CHAPTER_READ,
        description = "Mark a chapter as read (or unread). Use this " +
            "when the user says 'I finished chapter 3', 'mark this " +
            "as read', or 'I haven't actually read that chapter yet'.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "fictionId",
                description = "The stable id of the fiction. Use the " +
                    "active fiction's id from the chat context.",
            ),
            ToolParameter.IntParam(
                name = "chapterIndex",
                description = "Zero-based chapter index in reading " +
                    "order. Chapter 1 is index 0.",
                min = 0,
            ),
        ),
    )

    val setSpeed: ToolSpec = ToolSpec(
        name = SET_SPEED,
        description = "Set the playback speed slider. Use this when " +
            "the user asks to 'speed it up', 'slow down', 'set " +
            "playback to 1.5x', etc. The speed value is a " +
            "multiplier — 1.0 is normal speed, 0.5 is half speed, " +
            "2.0 is double speed.",
        parameters = listOf(
            ToolParameter.FloatParam(
                name = "speed",
                description = "Playback speed multiplier. 1.0 is " +
                    "normal speed.",
                min = SPEED_MIN,
                max = SPEED_MAX,
            ),
        ),
    )

    val openVoiceLibrary: ToolSpec = ToolSpec(
        name = OPEN_VOICE_LIBRARY,
        description = "Open the voice library screen so the user can " +
            "pick or download a different TTS voice. Use this when " +
            "the user asks to 'change the voice', 'pick a different " +
            "narrator', 'see what voices I have', or similar.",
        parameters = emptyList(),
    )

    val searchSources: ToolSpec = ToolSpec(
        name = SEARCH_SOURCES,
        description = "Search the user's enabled content sources for " +
            "books, stories, or articles. Use this whenever the user " +
            "asks you to find or recommend something to read — 'find " +
            "me a cultivation story', 'any good sci-fi on Royal Road?', " +
            "'what should I read next?'. Returns matching titles with " +
            "their author, source, and a stable id + source id you can " +
            "pass to get_book_details or add_to_shelf. Results are " +
            "aggregated across every enabled source unless you name one.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "query",
                description = "What to search for — a topic, genre, " +
                    "title fragment, or author. Plain words work best.",
            ),
            ToolParameter.StringParam(
                name = "source",
                description = "Optional. Restrict the search to a single " +
                    "source by its id or display name (e.g. 'royalroad' " +
                    "or 'Royal Road'). Omit to search all enabled sources.",
                required = false,
            ),
            ToolParameter.IntParam(
                name = "limit",
                description = "Optional. Maximum number of results to " +
                    "return (default $SEARCH_LIMIT_DEFAULT, max " +
                    "$SEARCH_LIMIT_MAX).",
                required = false,
                min = 1,
                max = SEARCH_LIMIT_MAX,
            ),
        ),
    )

    val getBookDetails: ToolSpec = ToolSpec(
        name = GET_BOOK_DETAILS,
        description = "Fetch full metadata for one fiction by id — " +
            "synopsis, chapter count, tags/genres, rating, word count, " +
            "and status. Use this after search_sources when the user " +
            "wants more detail on a specific result, or to decide " +
            "whether to recommend a book. Pass the `source` from the " +
            "search result so the lookup routes to the right backend.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "fictionId",
                description = "The stable id of the fiction, as returned " +
                    "by search_sources (the `id=` field).",
            ),
            ToolParameter.StringParam(
                name = "source",
                description = "Optional but recommended. The source id " +
                    "the fiction belongs to (the `source=` field from " +
                    "the search result). Omit and storyvox will try the " +
                    "enabled sources in turn.",
                required = false,
            ),
        ),
    )

    /** Ordered list of all tools. Iteration order is the order they
     *  appear in this file. */
    val ALL: List<ToolSpec> = listOf(
        addToShelf,
        queueChapter,
        markChapterRead,
        setSpeed,
        openVoiceLibrary,
        searchSources,
        getBookDetails,
    )
}
