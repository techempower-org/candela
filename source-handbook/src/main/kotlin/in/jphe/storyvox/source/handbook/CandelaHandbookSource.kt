package `in`.jphe.storyvox.source.handbook

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Candela Handbook source — a LOCAL provider (scaffolded by new-source.sh --local).
 *
 * The app's own user guide, shipped as a bundled, narrated book. There is
 * exactly ONE fiction — "The Candela Handbook" — whose chapters are the sections
 * of the manual (Getting Started, Voices, the Reader, Sync & Privacy, the
 * per-source setup walk-throughs, and the FAQ). Content is compiled from `docs/`
 * into `assets/handbook/` by `scripts/build-handbook-assets.py`, so the docs
 * stay canonical and the handbook can't drift silently; the snapshot version is
 * surfaced in the fiction description.
 *
 * No network, no permissions, default-enabled: the manual reads itself aloud
 * through the same TTS pipeline as any other book — the accessibility-first way
 * to ship help. Reads go through [CandelaHandbookReader]; the plain unit test
 * subclasses that reader with a fake. See docs/CONTRIBUTING-SOURCES.md
 * ("Local-provider sources") and :source-ocr.
 *
 * The @SourcePlugin id below is the SINGLE source of truth for this backend's
 * identity — do NOT add a SourceIds constant (that table is frozen).
 */
@SourcePlugin(
    id = "handbook",
    displayName = "Candela Handbook",
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Candela's own user guide — read aloud, fully offline",
    sourceUrl = "",
)
@Singleton
internal class CandelaHandbookSource @Inject constructor(
    private val reader: CandelaHandbookReader,
) : FictionSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Candela Handbook"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        singleBookPage()

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        singleBookPage()

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        if (genre.equals("Help", ignoreCase = true) || genre.equals("Guide", ignoreCase = true)) {
            singleBookPage()
        } else {
            FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val matches = term.isEmpty() || MATCH_TERMS.any { it.contains(term, ignoreCase = true) } ||
            term.contains("handbook", ignoreCase = true) ||
            term.contains("candela", ignoreCase = true) ||
            term.contains("help", ignoreCase = true) ||
            term.contains("guide", ignoreCase = true)
        return if (matches) singleBookPage() else {
            FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        if (fictionId != HANDBOOK_FICTION_ID) {
            return FictionResult.NotFound("unknown handbook fiction: $fictionId")
        }
        val manifest = reader.manifest()
        val chapters = manifest.chapters.map { ch ->
            ChapterInfo(
                id = ch.id,
                sourceChapterId = ch.id,
                index = ch.index,
                title = ch.title,
            )
        }
        return FictionResult.Success(
            FictionDetail(
                summary = summary(manifest.chapters.size),
                chapters = chapters,
                genres = GENRES,
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        if (fictionId != HANDBOOK_FICTION_ID) {
            return FictionResult.NotFound("unknown handbook fiction: $fictionId")
        }
        val manifest = reader.manifest()
        val chapter = manifest.chapters.firstOrNull { it.id == chapterId }
            ?: return FictionResult.NotFound("unknown handbook chapter: $chapterId")
        val plain = reader.chapterText(chapterId)
            ?: return FictionResult.NotFound("handbook chapter body missing: $chapterId")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(
                    id = chapter.id,
                    sourceChapterId = chapter.id,
                    index = chapter.index,
                    title = chapter.title,
                ),
                htmlBody = toHtml(plain),
                plainBody = plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.NotFound("the handbook cannot be followed")

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(GENRES)

    // ── helpers ──────────────────────────────────────────────────────────────

    private suspend fun singleBookPage(): FictionResult<ListPage<FictionSummary>> {
        val manifest = reader.manifest()
        return FictionResult.Success(
            ListPage(
                items = listOf(summary(manifest.chapters.size)),
                page = 1,
                hasNext = false,
            ),
        )
    }

    private fun summary(chapterCount: Int): FictionSummary =
        FictionSummary(
            id = HANDBOOK_FICTION_ID,
            sourceId = SOURCE_ID,
            title = "The Candela Handbook",
            author = "TechEmpower",
            description = "Candela's own guide, read aloud. Getting started, sources, " +
                "voices, the reader & teleprompter, sync & privacy, and per-source " +
                "setup walk-throughs — fully offline, no account.",
            tags = GENRES,
            status = FictionStatus.COMPLETED,
            chapterCount = chapterCount,
        )

    /**
     * Minimal plain-text → HTML for the reader pane. The bundled bodies are
     * already narration-clean plain text (the generator strips markdown), so we
     * only escape and wrap blank-line-separated blocks in paragraphs. TTS reads
     * [ChapterContent.plainBody]; this is just the on-screen rendering.
     */
    private fun toHtml(plain: String): String =
        plain.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { block ->
                val escaped = block
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br/>")
                "<p>$escaped</p>"
            }

    companion object {
        const val SOURCE_ID = "handbook"

        /**
         * The single fiction id. Stable across snapshots so a saved reading
         * position / library entry survives a docs regen.
         */
        const val HANDBOOK_FICTION_ID = "candela-handbook"

        private val GENRES = listOf("Help", "Guide")
        private val MATCH_TERMS = listOf("candela handbook", "manual", "documentation", "docs")
    }
}
