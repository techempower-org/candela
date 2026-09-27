package `in`.jphe.storyvox.data.briefing

import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.InboxRepository
import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Assembles a [BriefingItem] queue from a [BriefingConfig] (#1467).
 *
 * Per source: fetch the latest listing, take the top `count` (plus a small
 * over-fetch), and resolve each summary to a concrete playable chapter. The
 * [BriefingSources.INBOX] pseudo-source reads unread Inbox new-chapter events
 * instead — already concrete chapters, no network. Sources are fetched
 * **concurrently** and **failure-tolerantly** — a source that errors (network,
 * auth-gated, off) contributes nothing rather than aborting the whole
 * briefing. Ordering, cross-source dedupe and the episode cap are
 * [BriefingPlanner.assemble]'s job, so the episode reads in config order.
 *
 * No briefing-specific fetchers: every input goes through the existing
 * [FictionRepository] / [InboxRepository] paths.
 */
interface BriefingBuilder {
    /** Build the ordered, playable queue. Empty when nothing resolved. */
    suspend fun build(config: BriefingConfig): List<BriefingItem>
}

class DefaultBriefingBuilder @Inject constructor(
    private val repo: FictionRepository,
    private val inbox: InboxRepository,
) : BriefingBuilder {

    override suspend fun build(config: BriefingConfig): List<BriefingItem> = coroutineScope {
        val active = BriefingPlanner.activeQuotas(config)
        val fetched = active
            .map { quota -> async { runCatching { itemsForSource(quota) }.getOrDefault(emptyList()) } }
            .awaitAll()
        BriefingPlanner.assemble(config, active.map { it.sourceId }.zip(fetched).toMap())
    }

    private suspend fun itemsForSource(quota: SourceQuota): List<BriefingItem> {
        if (quota.count <= 0) return emptyList()
        if (quota.sourceId == BriefingSources.INBOX) {
            return BriefingPlanner.inboxItems(inbox.observeAll().first(), quota.count + OVERFETCH)
        }
        val listing = repo.browseLatest(page = 1, sourceId = quota.sourceId)
        val summaries = (listing as? FictionResult.Success)?.value?.items.orEmpty()
            .take(quota.count + OVERFETCH)
        // Resolve each summary to its first playable chapter. `browseLatest`
        // already cached the row (so `refreshDetail` routes to the right
        // source); `refreshDetail(force)` hydrates the chapter list, which the
        // Room-backed `observeFiction` then emits. Each resolve is guarded so
        // one dud item doesn't drop the rest of the source. For the calendar
        // source the first chapter is "Today" — today's agenda/deadlines.
        return summaries.mapNotNull { summary ->
            runCatching { resolveFirstChapter(summary.id, summary.title, quota.sourceId) }.getOrNull()
        }
    }

    private suspend fun resolveFirstChapter(
        fictionId: String,
        title: String,
        sourceId: String,
    ): BriefingItem? {
        repo.refreshDetail(fictionId, force = true)
        // Wait (bounded) for a hydrated detail carrying at least one chapter.
        // Bounded so a source that never hydrates can't hang the whole build.
        val detail = withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
            repo.observeFiction(fictionId).first { it != null && it.chapters.isNotEmpty() }
        }
        val chapter = detail?.chapters?.firstOrNull() ?: return null
        return BriefingItem(
            fictionId = fictionId,
            chapterId = chapter.id,
            sourceId = sourceId,
            title = title,
        )
    }

    private companion object {
        /** Upper bound on waiting for one item's chapter list to hydrate. */
        const val DETAIL_TIMEOUT_MS = 15_000L

        /**
         * Extra candidates resolved per source so a cross-source duplicate or a
         * summary with no chapters doesn't leave the source short. Small: each
         * extra listing item costs one detail refresh.
         */
        const val OVERFETCH = 2
    }
}
