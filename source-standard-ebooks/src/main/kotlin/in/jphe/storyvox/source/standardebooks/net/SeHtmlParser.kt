package `in`.jphe.storyvox.source.standardebooks.net

import `in`.jphe.storyvox.data.text.htmlToInlineText

/**
 * Issue #375 — focused regex extractor over the public Standard Ebooks
 * listing and per-book HTML pages.
 *
 * Why regex instead of a full HTML parser: the pages are server-rendered,
 * RDFa-marked, and machine-friendly by intent — SE publishes an
 * `OpenSearchDescription` and emits schema.org microdata on every list
 * entry. The structural anchors we depend on
 * (`<li typeof="schema:Book" about="/ebooks/{author}/{book}">`,
 * `property="schema:name"`, `property="schema:image"`,
 * `property="schema:description"`, `<a rel="next">`) are part of SE's
 * public structured-data contract. A regex pass over those anchors is
 * faster, has no Android-XML-parser quirks, and produces the same
 * extraction shape an OPDS `<entry>` walker would. If SE ever ships a
 * public OPDS feed, the API client can swap to a `<feed><entry>` parser
 * and leave this file behind unchanged.
 *
 * Captured field text (tag-stripping + entity decode + whitespace
 * collapse) goes through the shared [htmlToInlineText] in core-data
 * (#1628) — one entity table for the whole app, decoding the curly
 * quotes / em-dashes / accents the old local `htmlDecode` left raw.
 * jsoup stays transitive to core-data; this module adds no jsoup dep.
 */
internal object SeHtmlParser {

    /**
     * Each book entry begins with
     * `<li typeof="schema:Book" about="/ebooks/{authorSlug}/{bookSlug}">`
     * and ends at the matching `</li>`. We capture the `about="..."` slug
     * pair as the entry's stable identifier, then extract within the
     * entry block to keep regexes scoped (and avoid cross-entry bleed
     * when the listing page contains 48 of them).
     */
    private val ENTRY_REGEX = Regex(
        """<li\s+typeof="schema:Book"\s+about="/ebooks/([^/"]+)/([^"]+)">([\s\S]*?)</li>\s*(?=<li\s+typeof="schema:Book"|</ol>)""",
    )

    /** Inside an entry: `<span property="schema:name">Title</span>`. */
    private val TITLE_REGEX = Regex(
        """<span\s+property="schema:name">([^<]+)</span>""",
    )

    /**
     * Inside an entry: `<p class="author"><a href="/ebooks/{author}">Author Name</a></p>`.
     * The `<p class="author">` wrapper is unique on the list-entry block
     * (the entry's outer link doesn't carry `class="author"`).
     */
    private val AUTHOR_REGEX = Regex(
        """<p\s+class="author">[\s\S]*?<a[^>]*>([^<]+)</a>""",
    )

    /**
     * Cover image — the `<img property="schema:image" src="...">` inside
     * the thumbnail container. SE serves both 1x and 2x via `<source>`
     * sets; we take the `<img src>` fallback because that's the URL
     * guaranteed to resolve everywhere (the AVIF/2x candidates need
     * format negotiation we don't do).
     */
    private val COVER_REGEX = Regex(
        """<img\s+src="([^"]+)"[^>]*\sproperty="schema:image"""",
    )

    /**
     * Tag chips inside the entry's `<ul class="tags">` block. SE links
     * each tag to `/subjects/<slug>`; we capture the slug because it
     * matches the `tags[]=` query param value (so a user tap from a
     * detail screen could route back to a same-tag browse later).
     */
    private val TAG_REGEX = Regex(
        """<a\s+href="/subjects/([^"]+)">([^<]+)</a>""",
    )

    /** Pagination footer carries `<a rel="next">` on every non-tail page. */
    private val HAS_NEXT_REGEX = Regex("""<a[^>]+rel="next"""")

    /**
     * Per-book page: the long-form description sits inside
     * `<section id="description">…<p>…</p>…</section>`. We collapse
     * every `<p>` block inside the section and strip residual tags.
     */
    private val DESCRIPTION_SECTION_REGEX = Regex(
        """<section\s+id="description">([\s\S]*?)</section>""",
    )
    private val PARAGRAPH_REGEX = Regex("""<p>([\s\S]*?)</p>""")

    /**
     * Parse one HTML listing page into the wire shape consumed by
     * [`StandardEbooksApi.popular`] / `.search` / `.latestUpdates`.
     * Returns an empty list (and `hasNext = false`) on a structurally
     * unexpected page so the source falls back to an empty grid rather
     * than throwing.
     */
    fun parseListPage(html: String): SeListPage {
        val entries = ENTRY_REGEX.findAll(html).mapNotNull { m ->
            val authorSlug = m.groupValues[1]
            val bookSlug = m.groupValues[2]
            val block = m.groupValues[3]

            val title = TITLE_REGEX.find(block)?.groupValues?.get(1)
                ?.htmlToInlineText()
                ?: return@mapNotNull null

            // Author falls back to the slug prettified if the chip is
            // missing — SE always emits the chip, but a defensive
            // default protects against a layout shift.
            val author = AUTHOR_REGEX.find(block)?.groupValues?.get(1)
                ?.htmlToInlineText()
                ?: authorSlug.replace('-', ' ').replaceFirstChar { it.uppercase() }

            val coverPath = COVER_REGEX.find(block)?.groupValues?.get(1)
            val coverUrl = coverPath?.let { absolutize(it) }

            val tags = TAG_REGEX.findAll(block)
                .map { it.groupValues[2].htmlToInlineText() }
                .toList()

            SeBookEntry(
                authorSlug = authorSlug,
                bookSlug = bookSlug,
                title = title,
                author = author,
                coverUrl = coverUrl,
                tags = tags,
            )
        }.toList()

        return SeListPage(
            results = entries,
            hasNext = HAS_NEXT_REGEX.containsMatchIn(html),
        )
    }

    /**
     * Pull the long-form description block out of a per-book page.
     * Returns null if the page didn't carry an `#description` section
     * — the caller falls back to the entry's tag list as a description.
     */
    fun parseBookDescription(html: String): String? {
        val section = DESCRIPTION_SECTION_REGEX.find(html)?.groupValues?.get(1)
            ?: return null
        val paragraphs = PARAGRAPH_REGEX.findAll(section)
            .map { it.groupValues[1].htmlToInlineText() }
            .filter { it.isNotEmpty() }
            .toList()
        if (paragraphs.isEmpty()) return null
        return paragraphs.joinToString("\n\n")
    }

    /**
     * Resolve a path-relative or scheme-relative URL against
     * `https://standardebooks.org`. SE emits absolute URLs for some
     * cover variants (e.g. the `media:thumbnail` URLs from the Atom
     * feed) but relative ones in the HTML listing thumbnails; cover the
     * common cases without taking on a URI dependency.
     */
    private fun absolutize(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "https://standardebooks.org$path"
        else -> "https://standardebooks.org/$path"
    }
}
