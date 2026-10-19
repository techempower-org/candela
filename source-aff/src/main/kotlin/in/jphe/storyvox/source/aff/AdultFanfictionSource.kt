package `in`.jphe.storyvox.source.aff

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterBody
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.aff.net.AdultFanfictionApi
import `in`.jphe.storyvox.source.aff.parser.AffParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adult-FanFiction.org (AFF) — an 18+ archive. Off by default, and the
 * `:source-aff` module is only linked into `:app` when the Gradle property
 * `candela.adultSources=true` is set (see app/build.gradle.kts), so the
 * default and Play builds never contain it.
 *
 * AFF splits fandoms across subdomains (`hp.`, `naruto.`, `original.` …);
 * those archives are this source's genres. Story numbers are per-archive, so
 * ids carry both: `aff:<archive>.<storyNo>`, chapters `…::<n>`.
 *
 * The `celeb` (real-person) archive is deliberately not offered.
 */
@SourcePlugin(
    id = "aff",
    displayName = "Adult-FanFiction.org",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "18+ fan fiction · adults only, off by default",
    sourceUrl = "https://www.adult-fanfiction.org",
    chipLabel = "AFF (18+)",
    searchHint = "Search AFF (current archive)",
    iconName = "AutoStories",
)
@Singleton
internal class AdultFanfictionSource @Inject constructor(
    private val api: AdultFanfictionApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Adult-FanFiction.org"

    override suspend fun popular(page: Int) = list(DEFAULT_ARCHIVE, "index.php?sort=reviews&page=${page.coerceAtLeast(1)}", page)

    override suspend fun latestUpdates(page: Int) = list(DEFAULT_ARCHIVE, "index.php?page=${page.coerceAtLeast(1)}", page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val archive = archiveFor(genre) ?: return FictionResult.NotFound("AFF: unknown archive '$genre'")
        return list(archive, "index.php?page=${page.coerceAtLeast(1)}", page)
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val page = query.page.coerceAtLeast(1)
        val archive = query.genres.firstNotNullOfOrNull { archiveFor(it) } ?: DEFAULT_ARCHIVE
        if (term.isEmpty()) return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        val q = URLEncoder.encode(term, "UTF-8")
        return list(archive, "search.php?q=$q&page=$page", page)
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val (archive, no) = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val base = api.archiveBase(archive)
        val r = api.get("$base/story.php?no=$no&chapter=1")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val story = AffParser.parseStory(r.value, base) ?: return FictionResult.NotFound("AFF: story not found")
        val fid = "$ID:$archive.$no"
        val first = AffParser.parseChapter(r.value, base)
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = story.title,
                    author = story.author.ifBlank { "Unknown" },
                    tags = listOfNotNull(story.category),
                    chapterCount = story.chapters.size,
                    companionSourceUrl = "$base/story.php?no=$no",
                ),
                chapters = story.chapters.mapIndexed { i, (n, t) ->
                    ChapterInfo(id = "$fid::$n", sourceChapterId = n.toString(), index = i, title = t)
                },
                prefetchedBodies = first?.let { mapOf("$fid::1" to ChapterBody(it.html, it.plain)) }.orEmpty(),
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val (archive, no) = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val n = chapterId.substringAfterLast("::").toIntOrNull() ?: return FictionResult.NotFound("bad chapter $chapterId")
        val base = api.archiveBase(archive)
        val r = api.get("$base/story.php?no=$no&chapter=$n")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val c = AffParser.parseChapter(r.value, base) ?: return FictionResult.NotFound("AFF: chapter $n not found")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = n.toString(), index = n - 1, title = c.title ?: "Chapter $n"),
                htmlBody = c.html,
                plainBody = c.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("AFF sign-in isn't supported")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("AFF sign-in isn't supported")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(ARCHIVES.keys.toList())

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun list(archive: String, path: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val base = api.archiveBase(archive)
        val r = api.get("$base/$path")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val parsed = AffParser.parseList(r.value, base)
        return FictionResult.Success(
            ListPage(
                items = parsed.entries.map { e ->
                    FictionSummary(
                        id = "$ID:$archive.${e.storyNo}",
                        sourceId = ID,
                        title = e.title,
                        author = e.author.ifBlank { "Unknown" },
                        description = e.description,
                        tags = e.tags,
                        chapterCount = e.chapters,
                    )
                },
                page = page.coerceAtLeast(1),
                hasNext = parsed.hasNext && parsed.entries.isNotEmpty(),
            ),
        )
    }

    private fun archiveFor(genre: String): String? =
        ARCHIVES[genre] ?: genre.lowercase().takeIf { it in ARCHIVES.values }

    private fun parseId(fictionId: String): Pair<String, String>? {
        val local = fictionId.removePrefix("$ID:")
        val archive = local.substringBefore('.', "")
        val no = local.substringAfter('.', "")
        if (archive !in ARCHIVES.values || no.isEmpty() || !no.all(Char::isDigit)) return null
        return archive to no
    }

    companion object {
        const val ID = "aff"
        const val DEFAULT_ARCHIVE = "hp"
        val ARCHIVES: Map<String, String> = linkedMapOf(
            "Harry Potter" to "hp",
            "Originals" to "original",
            "Anime" to "anime",
            "Anime (section 2)" to "anime2",
            "Manga" to "manga",
            "Naruto" to "naruto",
            "Bleach" to "bleach",
            "Inuyasha" to "inu",
            "Yu-Gi-Oh!" to "ygo",
            "Yu Yu Hakusho" to "yuyu",
            "Final Fantasy" to "ff",
            "Games" to "games",
            "Books" to "books",
            "Lord of the Rings" to "lotr",
            "Buffy" to "buffy",
            "TV" to "tv",
            "Movies" to "movies",
            "Cartoons" to "cartoon",
            "Comics" to "comics",
            "X-Men" to "xmen",
            "Non-English" to "ne",
        )
    }
}
