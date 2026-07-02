package `in`.jphe.storyvox.data.briefing

import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1467 — [DefaultBriefingBuilder] resolves each configured source's latest
 * items to playable chapters, preserving config order, honoring per-source
 * counts, and tolerating a source that fails or yields nothing.
 */
class BriefingBuilderTest {

    @Test fun `builds items across sources in config order, capped per quota`() = runTest {
        val repo = FakeRepo().apply {
            latest["hackernews"] = summaries("hackernews", 1, 2, 3) // 3 available…
            latest["arxiv"] = summaries("arxiv", 1, 2)
            everySummaryHasOneChapter()
        }
        val builder = DefaultBriefingBuilder(repo)

        val items = builder.build(
            BriefingConfig(listOf(SourceQuota("hackernews", 2), SourceQuota("arxiv", 5))),
        )

        // hackernews capped to 2; arxiv has only 2; config order preserved.
        assertEquals(
            listOf("hackernews:1", "hackernews:2", "arxiv:1", "arxiv:2"),
            items.map { it.fictionId },
        )
        assertEquals("chap-hackernews:1", items.first().chapterId)
    }

    @Test fun `a failing source contributes nothing but does not abort the briefing`() = runTest {
        val repo = FakeRepo().apply {
            latest["hackernews"] = FictionResult.NetworkError(message = "down")
            latest["arxiv"] = summaries("arxiv", 1)
            everySummaryHasOneChapter()
        }
        val builder = DefaultBriefingBuilder(repo)

        val items = builder.build(
            BriefingConfig(listOf(SourceQuota("hackernews", 3), SourceQuota("arxiv", 3))),
        )

        assertEquals(listOf("arxiv:1"), items.map { it.fictionId })
    }

    @Test fun `summaries whose detail has no chapters are skipped`() = runTest {
        val repo = FakeRepo().apply {
            latest["arxiv"] = summaries("arxiv", 1, 2)
            details["arxiv:1"] = detail("arxiv:1", chapters = emptyList()) // no playable chapter
            details["arxiv:2"] = detail(
                "arxiv:2",
                chapters = listOf(ChapterInfo(id = "chap-arxiv:2", sourceChapterId = "s2", index = 0, title = "T")),
            )
        }
        val builder = DefaultBriefingBuilder(repo)

        val items = builder.build(BriefingConfig(listOf(SourceQuota("arxiv", 5))))

        assertEquals(listOf("arxiv:2"), items.map { it.fictionId })
    }

    @Test fun `a zero-count quota fetches nothing`() = runTest {
        val repo = FakeRepo().apply {
            latest["arxiv"] = summaries("arxiv", 1)
            everySummaryHasOneChapter()
        }
        val builder = DefaultBriefingBuilder(repo)

        val items = builder.build(BriefingConfig(listOf(SourceQuota("arxiv", 0))))

        assertTrue(items.isEmpty())
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun summaries(source: String, vararg ns: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(
            ListPage(
                items = ns.map { n ->
                    FictionSummary(id = "$source:$n", sourceId = source, title = "$source #$n", author = "a")
                },
                page = 1,
            ),
        )

    private fun detail(id: String, chapters: List<ChapterInfo>): FictionDetail =
        FictionDetail(
            summary = FictionSummary(id = id, sourceId = id.substringBefore(':'), title = id, author = "a"),
            chapters = chapters,
        )

    /**
     * Hand-rolled [FictionRepository] fake — only the three methods the builder
     * calls (`browseLatest`, `refreshDetail`, `observeFiction`) are real; the
     * rest are unreachable for these tests.
     */
    private class FakeRepo : FictionRepository {
        val latest = mutableMapOf<String, FictionResult<ListPage<FictionSummary>>>()
        val details = mutableMapOf<String, FictionDetail?>()

        /** Convenience: give every summary in every `latest` listing a single chapter. */
        fun everySummaryHasOneChapter() {
            latest.values.forEach { res ->
                (res as? FictionResult.Success)?.value?.items?.forEach { s ->
                    details[s.id] = FictionDetail(
                        summary = s,
                        chapters = listOf(
                            ChapterInfo(id = "chap-${s.id}", sourceChapterId = s.id, index = 0, title = s.title),
                        ),
                    )
                }
            }
        }

        override suspend fun browseLatest(
            page: Int,
            sourceId: String,
        ): FictionResult<ListPage<FictionSummary>> =
            latest[sourceId] ?: FictionResult.Success(ListPage(emptyList(), page))

        override suspend fun refreshDetail(id: String, force: Boolean): FictionResult<Unit> =
            FictionResult.Success(Unit)

        override fun observeFiction(id: String): Flow<FictionDetail?> = flowOf(details[id])

        // ── unused by the builder ──
        override fun observeLibrary(): Flow<List<FictionSummary>> = TODO()
        override fun observeFollowsRemote(): Flow<List<FictionSummary>> = TODO()
        override fun observeIsInLibrary(id: String): Flow<Boolean> = TODO()
        override suspend fun browsePopular(page: Int, sourceId: String): FictionResult<ListPage<FictionSummary>> = TODO()
        override suspend fun browseByGenre(genre: String, page: Int, sourceId: String): FictionResult<ListPage<FictionSummary>> = TODO()
        override suspend fun search(query: `in`.jphe.storyvox.data.source.model.SearchQuery, sourceId: String): FictionResult<ListPage<FictionSummary>> = TODO()
        override suspend fun cacheBrowseListing(result: FictionResult<ListPage<FictionSummary>>): FictionResult<ListPage<FictionSummary>> = TODO()
        override suspend fun genres(sourceId: String): FictionResult<List<String>> = TODO()
        override suspend fun refreshRemoteFollows(): FictionResult<Unit> = TODO()
        override suspend fun addToLibrary(id: String, mode: `in`.jphe.storyvox.data.db.entity.DownloadMode?) = TODO()
        override suspend fun removeFromLibrary(id: String) = TODO()
        override suspend fun setDownloadMode(id: String, mode: `in`.jphe.storyvox.data.db.entity.DownloadMode?) = TODO()
        override suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?) = TODO()
        override suspend fun pinnedVoiceId(id: String): String? = TODO()
        override suspend fun setPlaybackSpeed(id: String, speed: Float?) = TODO()
        override fun observePlaybackSpeed(id: String): Flow<Float?> = TODO()
        override suspend fun setFollowedRemote(id: String, followed: Boolean): FictionResult<Unit> = TODO()
        override suspend fun markAllCaughtUp(): Int = TODO()
        override suspend fun addByUrl(url: String, preferredSourceId: String?): `in`.jphe.storyvox.data.repository.AddByUrlResult = TODO()
        override fun previewUrl(url: String): List<`in`.jphe.storyvox.data.source.RouteMatch> = TODO()
    }
}
