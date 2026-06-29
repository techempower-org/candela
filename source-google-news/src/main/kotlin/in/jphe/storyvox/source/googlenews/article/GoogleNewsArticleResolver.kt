package `in`.jphe.storyvox.source.googlenews.article

import `in`.jphe.storyvox.data.repository.GoogleNewsConfig
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import `in`.jphe.storyvox.source.readability.extract.ReadabilityExtractor
import `in`.jphe.storyvox.source.readability.net.ReadabilityFetcher
import javax.inject.Inject

/**
 * Issue #1295 — the real [ArticleResolver]: recovers a story's full article
 * text so the chapter narrates the body, not just the headline digest.
 *
 * Pipeline: opt-in gate → decode the `CBMi…` link to the publisher URL
 * ([GoogleNewsUrlDecoder]) → fetch the page (`:source-readability`'s
 * [ReadabilityFetcher]) → strip boilerplate ([ReadabilityExtractor]). **Every
 * step degrades to null**, which signals [GoogleNewsSource][in.jphe.storyvox.source.googlenews.GoogleNewsSource]
 * to use its always-available digest — a decode/fetch/extract failure must
 * never produce a broken chapter (issue #1295 acceptance).
 */
internal class GoogleNewsArticleResolver @Inject constructor(
    private val decoder: GoogleNewsUrlDecoder,
    private val fetcher: ReadabilityFetcher,
    private val extractor: ReadabilityExtractor,
    private val config: GoogleNewsConfig,
) : ArticleResolver {

    override suspend fun resolve(item: GoogleNewsItem): String? {
        // Opt-in gate (default OFF — ToS-gray + fragile).
        if (!config.isFullArticleTextEnabled()) return null

        val publisherUrl = decoder.decode(item.link) ?: return null

        val html = when (val r = fetcher.fetch(publisherUrl)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return null
        }

        return runCatching { extractor.extract(publisherUrl, html)?.contentText }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
