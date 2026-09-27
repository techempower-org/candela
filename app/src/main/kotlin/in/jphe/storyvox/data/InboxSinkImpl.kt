package `in`.jphe.storyvox.data

import dagger.Lazy
import `in`.jphe.storyvox.data.repository.AddByUrlResult
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.ocr.config.OcrDocumentStore
import `in`.jphe.storyvox.source.ocr.config.OcrPage
import `in`.jphe.storyvox.sync.domain.InboxDelivery
import `in`.jphe.storyvox.sync.domain.InboxItem
import `in`.jphe.storyvox.sync.domain.InboxLogic
import `in`.jphe.storyvox.sync.domain.InboxSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1469 — lands a pushed inbox item in the Library.
 *
 * - `url` items go through [FictionRepository.addByUrl], the same resolver
 *   Share → Candela and Magic-add use, then [FictionRepository.addToLibrary].
 *   Nobody is at the phone to answer the multi-backend chooser, so an
 *   ambiguous URL takes the resolver's top-ranked candidate.
 * - `text` items become a local text document in the on-device document
 *   store (the one OCR scans live in — plain text in DataStore, nothing
 *   leaves the phone again) and are added to the Library.
 *
 * Both collaborators are [Lazy]: FictionRepository pulls in the whole source
 * graph, some of which reaches back toward sync; deferring resolution keeps
 * `Set<Syncer>` → InboxSyncer → this sink out of any Dagger init cycle
 * (same #1309 posture as the source configs).
 */
@Singleton
class InboxSinkImpl @Inject constructor(
    private val fictions: Lazy<FictionRepository>,
    private val documents: Lazy<OcrDocumentStore>,
) : InboxSink {

    override suspend fun deliver(item: InboxItem): InboxDelivery = when (item.kind) {
        InboxItem.KIND_URL -> deliverUrl(item.url.orEmpty().trim())
        InboxItem.KIND_TEXT -> deliverText(item)
        else -> InboxDelivery.Rejected
    }

    private suspend fun deliverUrl(url: String): InboxDelivery {
        val repo = fictions.get()
        var result = repo.addByUrl(url)
        if (result is AddByUrlResult.MultipleMatches) {
            val top = result.candidates.firstOrNull() ?: return InboxDelivery.Rejected
            result = repo.addByUrl(url, preferredSourceId = top.sourceId)
        }
        val delivery = classify(result)
        if (result is AddByUrlResult.Success) repo.addToLibrary(result.fictionId)
        return delivery
    }

    private suspend fun deliverText(item: InboxItem): InboxDelivery {
        val chapters = InboxLogic.chaptersForText(item.text.orEmpty())
        if (chapters.isEmpty()) return InboxDelivery.Rejected
        val title = InboxLogic.titleForText(item)
        val pages = chapters.mapIndexed { i, body ->
            OcrPage(
                index = i,
                title = if (chapters.size == 1) title else "Part ${i + 1}",
                text = body,
            )
        }
        val fictionId = documents.get().save(title = title, pages = pages)
        fictions.get().addToLibrary(fictionId)
        return InboxDelivery.Delivered
    }

    companion object {
        /** Pure mapping of an add-by-URL outcome to an inbox delivery.
         *  Transient source failures retry on a later poll (bounded by
         *  InboxLimits.MAX_ATTEMPTS); structural ones are given up on. */
        fun classify(result: AddByUrlResult): InboxDelivery = when (result) {
            is AddByUrlResult.Success -> InboxDelivery.Delivered
            is AddByUrlResult.UnrecognizedUrl -> InboxDelivery.Rejected
            is AddByUrlResult.UnsupportedSource -> InboxDelivery.Rejected
            // Only reachable if the preferred-source retry itself came back
            // ambiguous, which the repository doesn't do — be safe anyway.
            is AddByUrlResult.MultipleMatches -> InboxDelivery.Rejected
            is AddByUrlResult.SourceFailure -> when (result.failure) {
                is FictionResult.NetworkError,
                is FictionResult.RateLimited,
                is FictionResult.Cloudflare,
                -> InboxDelivery.Retry
                is FictionResult.NotFound,
                is FictionResult.AuthRequired,
                -> InboxDelivery.Rejected
            }
        }
    }
}
