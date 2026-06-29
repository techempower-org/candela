package `in`.jphe.storyvox.feature.chat.tools

import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.llm.tools.StoryvoxToolSpecs
import `in`.jphe.storyvox.llm.tools.ToolHandler
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import `in`.jphe.storyvox.llm.tools.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Issue #216 / #1227 — concrete [ToolHandler] implementations wiring
 * the tool catalog to storyvox's data + playback + source layers.
 *
 * Constructed per-chat-turn by the ViewModel (cheap — a handful of
 * lambdas holding repo refs), passed into the provider's tool-aware
 * chat path via [ToolRegistry]. The chat's active fiction id is
 * captured at construction time; the AI's `fictionId` argument is
 * honored over the active fiction only when the user explicitly named
 * a different book — practical guard against the model "helpfully"
 * targeting the wrong book on a search-shaped prompt.
 *
 * Issue #1227 — `search_sources` / `get_book_details` reach the source
 * backends through [sourceRegistry], honoring the same enabled-set
 * projection the Browse screen uses (`sourcePluginsEnabled[id]` with a
 * `defaultEnabled` fallback). Auth-gated or unreachable sources are
 * skipped so one signed-out backend never sinks the whole search.
 */
class ChatToolHandlers(
    private val activeFictionId: String,
    private val shelfRepo: ShelfRepository,
    private val chapterRepo: ChapterRepository,
    private val fictionRepo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    private val settingsRepo: SettingsRepositoryUi,
    private val sourceRegistry: SourcePluginRegistry,
    /** Fired on `open_voice_library` calls. The Chat ViewModel relays
     *  this to ChatScreen, which navigates via the nav controller. */
    private val onOpenVoiceLibrary: () -> Unit,
) {
    /** Build a [ToolRegistry] bound to this handler instance.
     *  Idempotent — same registry every call. */
    fun registry(): ToolRegistry = ToolRegistry.build(
        specs = StoryvoxToolSpecs.ALL,
        lookup = ::handlerFor,
    )

    private fun handlerFor(name: String): ToolHandler = when (name) {
        StoryvoxToolSpecs.ADD_TO_SHELF -> ToolHandler { addToShelf(it) }
        StoryvoxToolSpecs.QUEUE_CHAPTER -> ToolHandler { queueChapter(it) }
        StoryvoxToolSpecs.MARK_CHAPTER_READ -> ToolHandler { markChapterRead(it) }
        StoryvoxToolSpecs.SET_SPEED -> ToolHandler { setSpeed(it) }
        StoryvoxToolSpecs.OPEN_VOICE_LIBRARY -> ToolHandler { openVoiceLibrary() }
        StoryvoxToolSpecs.SEARCH_SOURCES -> ToolHandler { searchSources(it) }
        StoryvoxToolSpecs.GET_BOOK_DETAILS -> ToolHandler { getBookDetails(it) }
        else -> ToolHandler {
            ToolResult.Error("Unknown tool: $name")
        }
    }

    // ── Handler implementations ────────────────────────────────────

    /** `add_to_shelf(fictionId, shelf)` — move a fiction onto one of
     *  the three predefined shelves. Validates the shelf name against
     *  [Shelf.ALL] (Reading / Read / Wishlist); anything else returns
     *  an Error so the AI surfaces "I can only add to those three". */
    internal suspend fun addToShelf(args: JsonObject): ToolResult {
        val fictionId = args["fictionId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: activeFictionId
        val shelfName = args["shelf"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(
                "Tell me which shelf — Reading, Read, or Wishlist.",
            )
        val shelf = Shelf.fromName(shelfName)
            ?: return ToolResult.Error(
                "\"$shelfName\" isn't one of the shelves. Pick Reading, Read, or Wishlist.",
            )
        val fiction = runCatching {
            fictionRepo.fictionById(fictionId).first()
        }.getOrNull()
            ?: return ToolResult.Error(
                "I couldn't find a fiction with id \"$fictionId\".",
            )
        shelfRepo.add(fictionId, shelf)
        return ToolResult.Success(
            "Added \"${fiction.title}\" to your ${shelf.displayName} shelf.",
        )
    }

    /** `queue_chapter(fictionId, chapterIndex)` — start playing a
     *  specific chapter. Resolves the chapter id by listing chapters
     *  for the fiction and indexing into the result. */
    internal suspend fun queueChapter(args: JsonObject): ToolResult {
        val fictionId = args["fictionId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: activeFictionId
        val index = args["chapterIndex"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error(
                "Tell me which chapter — the chapter index is zero-based.",
            )
        if (index < 0) {
            return ToolResult.Error(
                "Chapter index can't be negative (index 0 is chapter 1).",
            )
        }
        val chapters = runCatching {
            fictionRepo.chaptersFor(fictionId).first()
        }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            return ToolResult.Error(
                "I couldn't find any chapters for that fiction yet.",
            )
        }
        val chapter = chapters.getOrNull(index)
            ?: return ToolResult.Error(
                "Chapter index $index is out of range — this book has ${chapters.size} chapter(s).",
            )
        playback.startListening(
            fictionId = fictionId,
            chapterId = chapter.id,
            charOffset = 0,
            autoPlay = true,
        )
        return ToolResult.Success(
            "Queued \"${chapter.title}\" (chapter ${index + 1}) for playback.",
        )
    }

    /** `mark_chapter_read(fictionId, chapterIndex)` — flip the
     *  chapter's read flag to true. Resolves chapter id by index
     *  same way as [queueChapter]. */
    internal suspend fun markChapterRead(args: JsonObject): ToolResult {
        val fictionId = args["fictionId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: activeFictionId
        val index = args["chapterIndex"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error(
                "Tell me which chapter — the chapter index is zero-based.",
            )
        if (index < 0) {
            return ToolResult.Error(
                "Chapter index can't be negative.",
            )
        }
        val chapters = runCatching {
            fictionRepo.chaptersFor(fictionId).first()
        }.getOrDefault(emptyList())
        val chapter = chapters.getOrNull(index)
            ?: return ToolResult.Error(
                "Chapter index $index is out of range — this book has ${chapters.size} chapter(s).",
            )
        chapterRepo.markRead(chapter.id, read = true)
        return ToolResult.Success(
            "Marked \"${chapter.title}\" as read.",
        )
    }

    /** `set_speed(speed)` — set the playback speed slider. Clamps to
     *  [0.5..2.5] to match the slider's visible range; reports the
     *  clamped value in the success message so the AI can echo it
     *  accurately even when the user asked for something out-of-range. */
    internal suspend fun setSpeed(args: JsonObject): ToolResult {
        val raw = args["speed"]?.jsonPrimitive?.floatOrNull
            ?: return ToolResult.Error(
                "Tell me a speed — 1.0 is normal, 1.5 is faster, 0.8 is slower.",
            )
        val clamped = raw.coerceIn(
            StoryvoxToolSpecs.SPEED_MIN,
            StoryvoxToolSpecs.SPEED_MAX,
        )
        playback.setSpeed(clamped)
        // Persist the new default so it sticks across chapters —
        // matches what tapping the slider does (#312 made setSpeed
        // also persist), but we set both surfaces explicitly to be
        // safe across renderer changes.
        runCatching { settingsRepo.setDefaultSpeed(clamped) }
        val suffix = if (clamped != raw) " (clamped from ${"%.2f".format(raw)})" else ""
        return ToolResult.Success(
            "Set playback speed to ${"%.2f".format(clamped)}x$suffix.",
        )
    }

    /** `open_voice_library()` — navigate to Settings → Voices. No
     *  arguments. Fires the [onOpenVoiceLibrary] callback the
     *  ViewModel passed in; that hop into nav-land is the only
     *  place this handler talks to the UI. */
    internal suspend fun openVoiceLibrary(): ToolResult {
        onOpenVoiceLibrary()
        return ToolResult.Success("Opening the voice library.")
    }

    /** `search_sources(query, source?, limit?)` — aggregate a free-text
     *  search across the enabled source backends. Optionally narrowed
     *  to one source by id or display name. Auth-gated / unreachable
     *  sources are skipped (partial results beat a hard failure), and
     *  the rendered rows carry each hit's `id` + `source` so the model
     *  can chain into [getBookDetails] / [addToShelf]. */
    internal suspend fun searchSources(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Error("Tell me what to search for.")
        val sourceFilter = args["source"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: StoryvoxToolSpecs.SEARCH_LIMIT_DEFAULT)
            .coerceIn(1, StoryvoxToolSpecs.SEARCH_LIMIT_MAX)

        val searchable = enabledDescriptors().filter { it.supportsSearch }
        if (searchable.isEmpty()) {
            return ToolResult.Error(
                "No searchable sources are enabled right now — turn some " +
                    "on in Settings → Sources.",
            )
        }
        val targets = if (sourceFilter != null) {
            searchable.filter { it.matches(sourceFilter) }.ifEmpty {
                return ToolResult.Error(
                    "I don't have a searchable source called \"$sourceFilter\". " +
                        "Enabled: ${searchable.joinToString { it.displayName }}.",
                )
            }
        } else {
            searchable
        }

        val q = SearchQuery(term = query)
        // Fan the searches out concurrently — a sequential sweep over a
        // dozen network backends would stall the tool call for seconds.
        // Each source is independently runCatching-guarded so a backend
        // that throws / times out / comes back auth-gated or rate-limited
        // contributes nothing rather than sinking the whole search.
        // awaitAll preserves target order, so registry order survives.
        val hits = coroutineScope {
            targets.map { descriptor ->
                async {
                    val result = runCatching { descriptor.source.search(q) }.getOrNull()
                    if (result is FictionResult.Success) {
                        result.value.items.map { descriptor to it }
                    } else {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        if (hits.isEmpty()) {
            return ToolResult.Success(
                "No matches for \"$query\" across " +
                    "${targets.size} source(s).",
            )
        }
        val shown = hits.take(limit)
        val header = buildString {
            append("Found ${hits.size} result(s) for \"$query\"")
            if (hits.size > shown.size) append(" (showing ${shown.size})")
            append(":")
        }
        val rows = shown.joinToString("\n") { (descriptor, f) ->
            formatSearchRow(descriptor.displayName, f)
        }
        return ToolResult.Success("$header\n$rows")
    }

    /** `get_book_details(fictionId, source?)` — fetch the full detail
     *  record for one fiction. Routes to the named [source] when given
     *  (the fast path the search rows steer the model toward); falls
     *  back to trying each enabled source in turn when it's omitted. */
    internal suspend fun getBookDetails(args: JsonObject): ToolResult {
        val fictionId = args["fictionId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Error("Tell me which book — I need its fiction id.")
        val sourceFilter = args["source"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }

        val enabled = enabledDescriptors()
        if (enabled.isEmpty()) {
            return ToolResult.Error("No sources are enabled right now.")
        }
        // Named source first (and only, when it resolves); otherwise sweep
        // every enabled source until one returns a Success.
        val ordered = if (sourceFilter != null) {
            enabled.filter { it.matches(sourceFilter) }.ifEmpty { enabled }
        } else {
            enabled
        }
        for (descriptor in ordered) {
            val result = runCatching { descriptor.source.fictionDetail(fictionId) }.getOrNull()
            if (result is FictionResult.Success) {
                return ToolResult.Success(
                    formatBookDetails(descriptor.displayName, result.value),
                )
            }
        }
        return ToolResult.Error(
            "I couldn't fetch details for \"$fictionId\" from any enabled source.",
        )
    }

    // ── Source helpers ─────────────────────────────────────────────

    /** The enabled subset of registered sources, mirroring the Browse
     *  screen's projection: an explicit `sourcePluginsEnabled[id]` wins,
     *  else the plugin's `defaultEnabled`. Settings read failures
     *  degrade to "defaults only" rather than throwing. */
    private suspend fun enabledDescriptors(): List<SourcePluginDescriptor> {
        val enabledMap = runCatching { settingsRepo.settings.first().sourcePluginsEnabled }
            .getOrDefault(emptyMap())
        return sourceRegistry.descriptors.filter { d ->
            enabledMap[d.id] ?: d.defaultEnabled
        }
    }

    /** True when [needle] names this source by id or display name
     *  (case-insensitive) — the two handles the model is likely to use. */
    private fun SourcePluginDescriptor.matches(needle: String): Boolean =
        id.equals(needle, ignoreCase = true) ||
            displayName.equals(needle, ignoreCase = true)

    /** One compact, dual-purpose line per search hit: readable in the
     *  tool-call card AND parseable by the model (the `id=`/`source=`
     *  tail feeds [getBookDetails] / [addToShelf]). */
    private fun formatSearchRow(sourceName: String, f: FictionSummary): String {
        val parts = buildList {
            add("\"${f.title}\"")
            if (f.author.isNotBlank()) add("by ${f.author}")
            add("— $sourceName")
            f.rating?.let { add("★${"%.1f".format(it)}") }
            f.chapterCount?.let { add("$it ch") }
        }
        return "• ${parts.joinToString(" ")} [id=${f.id} source=${f.sourceId}]"
    }

    /** Multi-line metadata block for one fiction. Prefers the live
     *  table-of-contents size for the chapter count, falling back to the
     *  summary's hint; trims the synopsis so a long description can't
     *  blow out the model's context. */
    private fun formatBookDetails(sourceName: String, detail: FictionDetail): String {
        val s = detail.summary
        return buildString {
            append("\"${s.title}\"")
            if (s.author.isNotBlank()) append(" by ${s.author}")
            append(" — $sourceName\n")
            val chapters = detail.chapters.size.takeIf { it > 0 } ?: s.chapterCount
            chapters?.let { append("Chapters: $it\n") }
            detail.wordCount?.let { append("Words: ~${formatCount(it)}\n") }
            s.rating?.let { append("Rating: ${"%.1f".format(it)}/5\n") }
            detail.followers?.let { append("Followers: ${formatCount(it.toLong())}\n") }
            append("Status: ")
            append(s.status.name.lowercase().replaceFirstChar { it.uppercase() })
            append("\n")
            val tags = (detail.genres + s.tags).distinct().filter { it.isNotBlank() }
            if (tags.isNotEmpty()) {
                append("Tags: ${tags.take(12).joinToString(", ")}\n")
            }
            val desc = s.description?.trim()?.takeIf { it.isNotBlank() }
            if (desc != null) {
                append("\n")
                append(desc.take(700))
                if (desc.length > 700) append("…")
            }
        }.trimEnd()
    }

    /** Compact thousands/millions formatter for word + follower counts
     *  (`1234 → 1.2k`, `2_500_000 → 2.5M`). Keeps the detail block
     *  scannable without dragging in a locale-aware NumberFormat. */
    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
        n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}k"
        else -> n.toString()
    }
}
