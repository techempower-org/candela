package `in`.jphe.storyvox.source.googlenews.article

import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import javax.inject.Inject

/**
 * Issue #1238 — seam for (future) full-article text extraction.
 *
 * Google News obfuscates article URLs behind `CBMi…` redirects whose
 * real publisher URL can only be recovered via a fragile internal
 * `batchexecute` RPC (see the issue body for the live investigation).
 * v1 deliberately does NOT implement that decode — [NoOpArticleResolver]
 * returns null and [GoogleNewsSource][in.jphe.storyvox.source.googlenews.GoogleNewsSource]
 * falls back to a headline + publisher + related-coverage digest.
 *
 * When a future resolver can recover a publisher URL, it fetches the
 * page and hands the HTML to `:source-readability`'s `ReadabilityExtractor`,
 * returning the cleaned article text to prepend to the chapter body. That
 * decode + extraction is tracked as a follow-up issue; this interface is
 * the extension point so adding it later touches one binding, not the
 * source's control flow.
 */
internal interface ArticleResolver {

    /**
     * Resolved full-article plain text for [item], or null when the
     * article body can't be recovered — the v1 default, which signals
     * the source to use its digest fallback.
     */
    suspend fun resolve(item: GoogleNewsItem): String?
}

/** v1 no-op: the article body is never recoverable from the feed alone. */
internal class NoOpArticleResolver @Inject constructor() : ArticleResolver {
    override suspend fun resolve(item: GoogleNewsItem): String? = null
}
