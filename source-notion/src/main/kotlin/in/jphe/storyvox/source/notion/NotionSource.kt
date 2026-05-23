package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import `in`.jphe.storyvox.source.notion.config.NotionMode
import `in`.jphe.storyvox.source.notion.net.NotionApi
import `in`.jphe.storyvox.source.notion.net.NotionBlock
import `in`.jphe.storyvox.source.notion.net.NotionPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #233 — Notion as a fiction backend. Issue #390 — the bundled
 * default points at the techempower.org content database, so a fresh
 * install opens Browse → Notion and immediately surfaces TechEmpower
 * content as narratable audio.
 *
 * Data model:
 *  - One Notion **database** → the Browse catalog. Configured per-user.
 *  - Each **page** in the database → one [FictionSummary].
 *  - The page's top-level **blocks** split into chapters on every
 *    `heading_1` boundary. The lead (before the first `heading_1`) is
 *    chapter 0 / "Introduction" — same shape as `:source-wikipedia`,
 *    which makes long-form Notion docs behave like an article.
 *  - Sub-headings (`heading_2`, `heading_3`) and other blocks stay
 *    inline within their parent chapter's HTML so the chapter count
 *    stays sensible.
 *
 * Auth: PAT-based. Notion's REST API requires `Authorization: Bearer
 * <integration_token>` on every call — there is no anonymous tier and
 * Notion's "public share" pages don't expose a public REST surface.
 * Users supply an Internal Integration Token from
 * notion.so/my-integrations and share the database with that
 * integration. The token is stored encrypted in app config.
 *
 * Fiction IDs: `notion:<pageId-with-hyphens-stripped>`. Chapter IDs:
 * `notion:<pageId>::section-<index>` where `index` is the 0-based
 * heading-split section (0 = Introduction).
 */
@SourcePlugin(
    id = SourceIds.NOTION,
    displayName = "Notion",
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Notion databases or public pages as fictions · anonymous reader by default · PAT for private workspaces",
    sourceUrl = "https://www.notion.so",
)
@Singleton
internal class NotionSource @Inject constructor(
    private val api: NotionApi,
    private val anonymous: AnonymousNotionDelegate,
    private val config: NotionConfig,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.NOTION
    override val displayName: String = "Notion"

    /** Issue #472 — `*.notion.so/<workspace>/<slug-with-32-hex-id>` or
     *  bare `notion.so/<32-hex-id>`. The pageId is the trailing 32-char
     *  hex blob with hyphens stripped. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = NOTION_URL_PATTERN.matchEntire(url.trim()) ?: return null
        val pageId = m.groupValues[1].replace("-", "")
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.NOTION,
            fictionId = "${SourceIds.NOTION}:$pageId",
            confidence = 0.85f,
            label = "Notion page",
        )
    }

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Default"),
                FilterDimension.SortOption("last_update", "Newest"),
                FilterDimension.SortOption("title", "Title"),
            ),
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "last_update" -> SearchOrder.LAST_UPDATE
                    "title" -> SearchOrder.TITLE
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.mode == NotionMode.ANONYMOUS_PUBLIC) {
            return anonymous.popular(state, page)
        }
        // Notion paginates via opaque `start_cursor` strings, not integer
        // page numbers. Our BrowsePaginator drives 1-indexed page numbers;
        // we collapse "first call" = page 1 = cursor=null. Page 2+ from a
        // cold start can't actually resolve the right cursor without
        // having seen page 1's response, so storyvox's paginator always
        // calls page 1 first and we forward the cursor through the
        // ListPage.nextCursor seam (which doesn't exist today). For v1
        // we surface the first 100 results only; pagination follow-up
        // is a separate ticket.
        //
        // Notion's default sort = `last_edited_time` desc, which reads
        // as "most-recently-updated" — perfect for Popular when the user
        // is following an active content team's Notion DB.
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        return api.queryDatabase(cursor = null, pageSize = 100).map { list ->
            ListPage(
                items = list.results.mapNotNull { it.toSummary() },
                page = 1,
                hasNext = list.hasMore,
            )
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Same shape — Notion's default sort already orders by last
        // edited time (descending), so "popular" and "new releases"
        // resolve identically. If we ever want a separate Popular
        // ordering, we'd post a sort body to /databases/{id}/query;
        // for v1 the two tabs are intentionally aligned.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Notion has no built-in genre faceting. Out of scope for v1;
        // users can search by keyword instead.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.mode == NotionMode.ANONYMOUS_PUBLIC) {
            return anonymous.search(state, query.term)
        }
        // v1 — client-side filter over the database's first 100 pages.
        // Notion's /search endpoint exists but searches the user's whole
        // workspace, not just the configured database; that's the wrong
        // shape (storyvox is per-database, not per-workspace). A
        // server-side filter via /databases/{id}/query with a `filter`
        // body is the follow-up.
        val term = query.term.trim().lowercase()
        return api.queryDatabase(cursor = null, pageSize = 100).map { list ->
            val items = list.results.mapNotNull { it.toSummary() }
                .filter { summary ->
                    term.isEmpty() ||
                        summary.title.lowercase().contains(term) ||
                        summary.description?.lowercase()?.contains(term) == true
                }
            ListPage(items = items, page = 1, hasNext = false)
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val state = config.current()
        if (state.mode == NotionMode.ANONYMOUS_PUBLIC) {
            return anonymous.fictionDetail(state, fictionId)
        }
        val pageId = fictionId.toPageId()
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")
        // Two-call pattern: page metadata + block children. Same shape
        // as :source-wikipedia uses for summary + html.
        val pageResult = api.page(pageId)
        val page = when (pageResult) {
            is FictionResult.Success -> pageResult.value
            is FictionResult.Failure -> return pageResult
        }
        val blocksResult = api.pageBlocks(pageId)
        val blocks = when (blocksResult) {
            is FictionResult.Success -> blocksResult.value
            is FictionResult.Failure -> return blocksResult
        }
        val sections = splitOnHeading1(blocks)
        val chapters = sections.mapIndexed { idx, section ->
            ChapterInfo(
                id = chapterIdFor(fictionId, idx),
                sourceChapterId = "section-$idx",
                index = idx,
                title = section.title,
                publishedAt = null,
            )
        }
        val summary = page.toSummary()?.copy(chapterCount = chapters.size)
            ?: FictionSummary(
                id = fictionId,
                sourceId = SourceIds.NOTION,
                title = "Untitled Notion page",
                author = "Notion",
                description = null,
                status = FictionStatus.ONGOING,
                chapterCount = chapters.size,
            )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val state = config.current()
        if (state.mode == NotionMode.ANONYMOUS_PUBLIC) {
            return anonymous.chapter(state, fictionId, chapterId)
        }
        val pageId = fictionId.toPageId()
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")
        val sectionIndex = chapterId.substringAfterLast("::section-", "")
            .toIntOrNull()
            ?: return FictionResult.NotFound("Notion chapter id not recognized: $chapterId")
        return when (val r = api.pageBlocks(pageId)) {
            is FictionResult.Success -> {
                val sections = splitOnHeading1(r.value)
                val section = sections.getOrNull(sectionIndex)
                    ?: return FictionResult.NotFound(
                        "Section $sectionIndex not found in Notion page $pageId",
                    )
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = "section-$sectionIndex",
                    index = sectionIndex,
                    title = section.title,
                )
                val html = section.blocks.joinToString("\n") { it.toHtml() }
                val plain = section.blocks.joinToString("\n\n") { it.toPlainText() }
                    .trim()
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = html,
                        plainBody = plain,
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Notion has no follow concept; the database membership IS the
        // follow list. Browse → Notion serves the same purpose.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}

// ─── helpers ──────────────────────────────────────────────────────────

/** Issue #472 — Notion page URL. The pageId is a 32-char hex blob,
 *  optionally hyphenated as `8-4-4-4-12`. We accept both forms. The
 *  pattern matches both bare workspace.notion.so/{id} and the
 *  slug+id form workspace.notion.so/{slug-with-id-at-end}. */
internal val NOTION_URL_PATTERN: Regex = Regex(
    """^https?://(?:[\w-]+\.)?notion\.(?:so|site)/(?:[\w-]+/)?(?:[\w-]*-)?([0-9a-f]{32}|(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Compose a stable Notion fiction id from a page id. */
internal fun notionFictionId(pageId: String): String =
    "notion:" + pageId.replace("-", "")

internal fun chapterIdFor(fictionId: String, sectionIndex: Int): String =
    "${fictionId}::section-$sectionIndex"

/** Compose a chapter id by binding a Notion page id to a parent fiction
 *  id. Used by anonymous-mode delegate where each chapter corresponds
 *  to one underlying Notion page (a curated guide, a database row, an
 *  About/Donate page) rather than a section-index offset. */
internal fun chapterIdFor(fictionId: String, pageId: String): String =
    "${fictionId}::${pageId.replace("-", "")}"

/** Strip the `notion:` prefix and return the canonical page id (with
 *  hyphens removed). Returns null on ids that don't carry the prefix. */
internal fun String.toPageId(): String? =
    if (startsWith("notion:")) removePrefix("notion:").substringBefore("::").replace("-", "")
    else null

/** Strip the `notion:` prefix and return the raw fiction id portion —
 *  for anonymous-mode fictions this is a section name ("guides",
 *  "resources", "about", "donate") rather than a Notion page id.
 *  Returns null on ids that don't carry the prefix. */
internal fun decodeFictionId(fictionId: String): String? =
    if (fictionId.startsWith("notion:")) fictionId.removePrefix("notion:")
    else null

/**
 * Internal section type — one chapter's title + ordered blocks.
 */
internal data class NotionSection(
    val title: String,
    val blocks: List<NotionBlock>,
)

/**
 * Split a flat block list into chapter sections on every `heading_1`
 * boundary. Section 0 (the "lead" before the first heading_1) is
 * titled "Introduction" unless empty. Empty leads are dropped.
 *
 * Same shape as :source-wikipedia's `splitTopLevelSections`, adapted
 * for Notion's flat block list (no nested `<section>` markup).
 */
internal fun splitOnHeading1(blocks: List<NotionBlock>): List<NotionSection> {
    if (blocks.isEmpty()) {
        // Empty page still gets one "Introduction" chapter so the
        // FictionDetail screen has something to anchor playback to —
        // the reader shows an empty body, the engine narrates nothing.
        return listOf(NotionSection("Introduction", emptyList()))
    }
    val sections = mutableListOf<NotionSection>()
    var currentTitle: String = "Introduction"
    var currentBlocks = mutableListOf<NotionBlock>()
    for (block in blocks) {
        if (block.type == "heading_1") {
            // Close out the prior section if it had content, then start a
            // new one titled with this heading's text.
            if (currentBlocks.isNotEmpty()) {
                sections.add(NotionSection(currentTitle, currentBlocks.toList()))
            }
            currentTitle = extractRichText(block.heading1) ?: "Section ${sections.size + 1}"
            currentBlocks = mutableListOf()
            // Don't include the heading_1 itself in the chapter body;
            // the chapter title duplicates it.
            continue
        }
        currentBlocks.add(block)
    }
    if (currentBlocks.isNotEmpty()) {
        sections.add(NotionSection(currentTitle, currentBlocks.toList()))
    }
    // Fully-empty page = one "Introduction" section with no blocks, so
    // FictionDetail still has a chapter to anchor playback (the reader
    // shows an empty body, the engine narrates nothing — same as a
    // blank Wikipedia article).
    if (sections.isEmpty()) {
        sections.add(NotionSection("Introduction", emptyList()))
    }
    return sections
}

/**
 * Project a NotionPage into a [FictionSummary]. Returns null if no
 * usable title can be extracted (a property of type "title" must
 * exist and contain at least one rich-text segment).
 *
 * Notion databases have one mandatory title property, but its *name*
 * varies per database (often "Name", sometimes "Title", sometimes
 * something domain-specific). We find it by type rather than by name:
 * scan the `properties` map for the first entry whose JSON shape
 * declares `"type": "title"`, then extract the rich-text array under
 * that property's `title` key.
 */
internal fun NotionPage.toSummary(): FictionSummary? {
    val title = extractTitleProperty(properties) ?: return null
    if (title.isBlank()) return null
    val description = extractDescriptionProperty(properties)
    val coverUrl = cover?.let { it.external?.url?.ifBlank { null } ?: it.file?.url?.ifBlank { null } }
    return FictionSummary(
        id = notionFictionId(id),
        sourceId = SourceIds.NOTION,
        title = title,
        author = extractAuthorProperty(properties) ?: "Notion",
        description = description,
        coverUrl = coverUrl,
        tags = extractTagsProperty(properties),
        status = FictionStatus.ONGOING,
    )
}

/** Pull the first property of type `title` from a Notion page's
 *  property map and return the concatenated rich-text content. */
internal fun extractTitleProperty(properties: Map<String, JsonElement>): String? {
    for ((_, v) in properties) {
        val obj = v as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type == "title") {
            val arr = obj["title"]?.jsonArray ?: continue
            return joinRichText(arr).ifBlank { null }
        }
    }
    return null
}

/** Pull a free-form "description" / "summary" / "abstract" property,
 *  if present. Notion databases vary wildly here; we check a couple of
 *  common names then any rich_text property in declaration order. */
internal fun extractDescriptionProperty(properties: Map<String, JsonElement>): String? {
    val preferredNames = listOf("Description", "description", "Summary", "summary", "Abstract", "abstract")
    for (name in preferredNames) {
        val v = properties[name] as? JsonObject ?: continue
        val type = v["type"]?.jsonPrimitive?.contentOrNull ?: continue
        if (type == "rich_text") {
            val arr = v["rich_text"]?.jsonArray ?: continue
            val s = joinRichText(arr)
            if (s.isNotBlank()) return s
        }
    }
    // Fallback — first non-empty rich_text property in declaration order.
    for ((name, v) in properties) {
        if (name in preferredNames) continue
        val obj = v as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type == "rich_text") {
            val arr = obj["rich_text"]?.jsonArray ?: continue
            val s = joinRichText(arr)
            if (s.isNotBlank()) return s
        }
    }
    return null
}

/** Pull a "By" / "Author" / "Created by" property if present. */
internal fun extractAuthorProperty(properties: Map<String, JsonElement>): String? {
    val preferredNames = listOf("Author", "author", "By", "by", "Authors", "authors")
    for (name in preferredNames) {
        val v = properties[name] as? JsonObject ?: continue
        val type = v["type"]?.jsonPrimitive?.contentOrNull ?: continue
        when (type) {
            "rich_text" -> {
                val arr = v["rich_text"]?.jsonArray ?: continue
                val s = joinRichText(arr)
                if (s.isNotBlank()) return s
            }
            "people" -> {
                val arr = v["people"]?.jsonArray ?: continue
                val names = arr.mapNotNull { p ->
                    (p as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                }
                if (names.isNotEmpty()) return names.joinToString(", ")
            }
            "select" -> {
                val sel = v["select"] as? JsonObject ?: continue
                val s = sel["name"]?.jsonPrimitive?.contentOrNull
                if (!s.isNullOrBlank()) return s
            }
        }
    }
    return null
}

/** Pull a "Tags" / "Categories" multi-select property, if present. */
internal fun extractTagsProperty(properties: Map<String, JsonElement>): List<String> {
    val preferredNames = listOf("Tags", "tags", "Categories", "categories", "Topics", "topics")
    for (name in preferredNames) {
        val v = properties[name] as? JsonObject ?: continue
        val type = v["type"]?.jsonPrimitive?.contentOrNull ?: continue
        if (type == "multi_select") {
            val arr = v["multi_select"]?.jsonArray ?: continue
            val names = arr.mapNotNull { entry ->
                (entry as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            }
            if (names.isNotEmpty()) return names
        }
    }
    return emptyList()
}

/**
 * Pull a heading's title text from its `heading_1` / `heading_2` /
 * `heading_3` payload. Notion stores the heading text as a
 * `rich_text` array under the type-named key; we concatenate.
 */
internal fun extractRichText(payload: JsonElement?): String? {
    val obj = payload as? JsonObject ?: return null
    val arr = obj["rich_text"]?.jsonArray ?: return null
    val text = joinRichText(arr)
    return text.ifBlank { null }
}

/**
 * Concatenate a Notion `rich_text` array into a plain-text string.
 * Each element is `{ type, plain_text, annotations, ... }`; the
 * `plain_text` field carries the user-visible text without HTML
 * markup. We use `plain_text` rather than reconstructing from
 * type-specific payloads because Notion guarantees plain_text is
 * always populated, even for `mention` / `equation` elements.
 */
internal fun joinRichText(array: JsonArray): String {
    val sb = StringBuilder()
    for (e in array) {
        val obj = e as? JsonObject ?: continue
        val plain = obj["plain_text"]?.jsonPrimitive?.contentOrNull ?: continue
        sb.append(plain)
    }
    return sb.toString()
}

/**
 * Render a Notion block to a fragment of HTML for the reader view.
 * Unsupported block types render as empty strings so the reader
 * doesn't show raw JSON. The TTS pipeline ignores tags; only the
 * concatenated text matters for narration.
 */
internal fun NotionBlock.toHtml(): String = when (type) {
    "paragraph" -> {
        val text = htmlEscape(extractRichText(paragraph) ?: "")
        if (text.isBlank()) "" else "<p>$text</p>"
    }
    "heading_2" -> {
        val text = htmlEscape(extractRichText(heading2) ?: "")
        if (text.isBlank()) "" else "<h2>$text</h2>"
    }
    "heading_3" -> {
        val text = htmlEscape(extractRichText(heading3) ?: "")
        if (text.isBlank()) "" else "<h3>$text</h3>"
    }
    "bulleted_list_item" -> {
        val text = htmlEscape(extractRichText(bulletedListItem) ?: "")
        if (text.isBlank()) "" else "<li>$text</li>"
    }
    "numbered_list_item" -> {
        val text = htmlEscape(extractRichText(numberedListItem) ?: "")
        if (text.isBlank()) "" else "<li>$text</li>"
    }
    "quote" -> {
        val text = htmlEscape(extractRichText(quote) ?: "")
        if (text.isBlank()) "" else "<blockquote>$text</blockquote>"
    }
    "callout" -> {
        val text = htmlEscape(extractRichText(callout) ?: "")
        if (text.isBlank()) "" else "<aside>$text</aside>"
    }
    "code" -> {
        val text = htmlEscape(extractRichText(code) ?: "")
        if (text.isBlank()) "" else "<pre><code>$text</code></pre>"
    }
    "divider" -> "<hr/>"
    "toggle" -> {
        val text = htmlEscape(extractRichText(toggle) ?: "")
        if (text.isBlank()) "" else "<p>$text</p>"
    }
    "to_do" -> {
        val text = htmlEscape(extractRichText(toDo) ?: "")
        if (text.isBlank()) "" else "<p>$text</p>"
    }
    else -> ""
}

/** Plain-text projection of a block for TTS. Strips all markup; only
 *  the text content matters. */
internal fun NotionBlock.toPlainText(): String = when (type) {
    "paragraph" -> extractRichText(paragraph) ?: ""
    "heading_2" -> extractRichText(heading2) ?: ""
    "heading_3" -> extractRichText(heading3) ?: ""
    "bulleted_list_item" -> extractRichText(bulletedListItem) ?: ""
    "numbered_list_item" -> extractRichText(numberedListItem) ?: ""
    "quote" -> extractRichText(quote) ?: ""
    "callout" -> extractRichText(callout) ?: ""
    "code" -> extractRichText(code) ?: ""
    "divider" -> ""
    "toggle" -> extractRichText(toggle) ?: ""
    "to_do" -> extractRichText(toDo) ?: ""
    else -> ""
}

/** Minimal HTML escape — angle brackets, ampersand, double-quote. */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
