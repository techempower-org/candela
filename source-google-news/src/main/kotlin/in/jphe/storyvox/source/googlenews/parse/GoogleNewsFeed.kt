package `in`.jphe.storyvox.source.googlenews.parse

/**
 * Issue #1238 — parsed Google News RSS feed.
 *
 * A thin, read-only shape produced by [GoogleNewsParser]. One feed maps
 * to one storyvox section-fiction; each [GoogleNewsItem] becomes a
 * chapter.
 */
internal data class GoogleNewsFeed(
    val title: String,
    val items: List<GoogleNewsItem>,
)

/**
 * One Google News story (`<item>`).
 *
 * Field notes (all derived from the live feed shape, see issue body):
 *  - [title] is the headline with the trailing " - Publisher" suffix
 *    stripped when [publisher] could be recovered.
 *  - [publisher] comes from the `<source>` element when present, else
 *    the title suffix, else "".
 *  - [link] is the obfuscated `news.google.com/rss/articles/CBMi…`
 *    redirect — kept for attribution / a future resolver, NOT a clean
 *    publisher URL.
 *  - [guid] is stable per article (`<guid isPermaLink="false">`), used
 *    to mint a stable chapter id.
 *  - [relatedHeadlines] are the sibling links Google clusters into the
 *    `<description>` `<ol>` (related coverage) — headlines only, the
 *    feed never carries article body text.
 */
internal data class GoogleNewsItem(
    val title: String,
    val publisher: String,
    val link: String,
    val guid: String,
    val publishedAtEpochMs: Long?,
    val relatedHeadlines: List<String>,
)
