package `in`.jphe.storyvox.source.googlenews.article

import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem

/**
 * Issue #1238 / #1295 — seam for full-article text extraction.
 *
 * Google News obfuscates article URLs behind `CBMi…` redirects whose real
 * publisher URL can only be recovered via a fragile internal `batchexecute`
 * RPC. [GoogleNewsArticleResolver] (issue #1295) implements that decode +
 * `:source-readability` extraction; it is gated opt-in (default OFF) and
 * returns null whenever the flag is off or any step fails, which signals
 * [GoogleNewsSource][in.jphe.storyvox.source.googlenews.GoogleNewsSource]
 * to fall back to its headline + publisher + related-coverage digest.
 *
 * Keeping this an interface means the resolver swaps behind a single Hilt
 * binding without touching the source's control flow.
 */
internal interface ArticleResolver {

    /**
     * Resolved full-article plain text for [item], or null when the article
     * body can't be recovered — signals the source to use its digest fallback.
     */
    suspend fun resolve(item: GoogleNewsItem): String?
}
