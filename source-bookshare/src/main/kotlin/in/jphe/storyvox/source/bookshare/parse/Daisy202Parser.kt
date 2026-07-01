package `in`.jphe.storyvox.source.bookshare.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/**
 * Issue #1293 — DAISY 2.02 (NCC) text extractor. Follow-up to #1290's DAISY 3
 * [DaisyParser]; produces the same [DaisyBook] / [DaisyChapter] shape.
 *
 * A DAISY 2.02 book is a **multi-file package** (unlike DAISY 3's single
 * self-contained DTBook XML):
 *
 *  - `ncc.html` — XHTML navigation: a flat run of `<h1>`..`<h6>` headings
 *    (`<hN id="ncc_N"><a href="file.smil#frag">Title</a>`) plus
 *    `<span class="page-*">` page markers. `<head>` carries `dc:title` /
 *    `dc:creator`.
 *  - `*.smil` — sync: `<par id="par_N"><text src="content.html#frag" id="txt_N"/></par>`.
 *    The heading's `#frag` is a SMIL anchor — per the 2.02 spec it may target a
 *    `<text id>` **or** a `<par id>` (the recommended form); either way it
 *    resolves to a `<text src>` that points into a content document.
 *  - content `*.html` — the actual prose (text lives inside `<a>` link text),
 *    every block carrying an id.
 *
 * For a TTS app we only need the text (we re-narrate), so the algorithm is:
 *  1. parse `ncc.html` → ordered headings (title + `smil#frag`); skip page spans.
 *  2. per heading, resolve `smil#frag` (a `<par>` or `<text>` anchor) → a
 *     `<text src>` → the chapter's START content-document fragment id.
 *  3. walk the content document ONCE, capturing all text (block boundaries split
 *     paragraphs; unknown tags are inline so text is never dropped; `page-*`
 *     class spans + `pagenum`/`noteref` are skipped), switching chapters when an
 *     element's id is a chapter-start id.
 *
 * Mirrors [`EpubParser`][in.jphe.storyvox.source.epub.parse.EpubParser]'s
 * XmlPullParser + zip-walk posture. Named HTML entities (`&nbsp;`, `&mdash;`,
 * `&eacute;`, …) are resolved to their literal characters up front so XHTML
 * content doesn't throw on the strict XML pull-parser and non-ASCII text isn't
 * lost; the five predefined XML entities are preserved for the parser and
 * unknown entities fall back to a space. NOT handled: protected (PDTB) packages
 * (the #1002 partnership).
 */
object Daisy202Parser {

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BLOCK_TAGS = setOf(
        "p", "list", "li", "dl", "dt", "dd", "blockquote", "note", "sidebar",
        "prodnote", "table", "tr", "td", "th", "caption", "linegroup", "line",
        "poem", "byline", "dateline", "epigraph", "author", "div", "bridgehead",
    ) + HEADING_TAGS
    private val SKIP_TEXT_TAGS = setOf("pagenum", "noteref", "linenum")

    private data class NccHeading(val id: String, val title: String, val href: String)
    private data class NccDoc(val title: String?, val author: String?, val headings: List<NccHeading>)
    private data class ChapterBody(val html: String, val plain: String)

    /** Parse a DAISY 2.02 package from a zip's raw bytes. */
    fun parseFromBytes(bytes: ByteArray): DaisyBook = parsePackage(readAllEntries(bytes))

    /**
     * Parse a DAISY 2.02 package given its files keyed by in-package path
     * (e.g. `"book/ncc.html"`). The test harness builds this from a real
     * public-domain sample; production feeds it a zip via [parseFromBytes].
     */
    fun parsePackage(entries: Map<String, ByteArray>): DaisyBook {
        try {
            val nccKey = entries.keys.firstOrNull { it.substringAfterLast('/').equals("ncc.html", true) }
                ?: throw DaisyParseException("DAISY 2.02 package has no ncc.html")
            val baseDir = nccKey.substringBeforeLast('/', "")
            val ncc = parseNcc(decode(entries.getValue(nccKey)))

            // Heading → (content-file key, chapter-start fragment id), via SMIL.
            data class Ref(val headingId: String, val title: String, val contentKey: String?, val frag: String?)
            val refs = ncc.headings.map { h ->
                val smilName = h.href.substringBefore('#')
                val smilFrag = h.href.substringAfter('#', "")
                val smilKey = resolveKey(entries, baseDir, smilName)
                val src = smilKey?.let { findContentSrc(decode(entries.getValue(it)), smilFrag) }
                val contentName = src?.substringBefore('#')
                val frag = src?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }
                Ref(h.id, h.title, contentName?.let { resolveKey(entries, baseDir, it) }, frag)
            }

            // Walk each referenced content document once; collect bodies by heading id.
            val byContentFile = refs.filter { it.contentKey != null && it.frag != null }
                .groupBy { it.contentKey!! }
                .mapValues { (_, group) -> group.associate { it.frag!! to it.headingId } }
            val bodies = mutableMapOf<String, ChapterBody>()
            for ((contentKey, fragToHeading) in byContentFile) {
                walkContent(decode(entries.getValue(contentKey)), fragToHeading, bodies)
            }

            val chapters = refs.map { ref ->
                val body = bodies[ref.headingId]
                DaisyChapter(
                    id = ref.headingId,
                    title = ref.title.takeIf { it.isNotEmpty() } ?: ref.headingId,
                    htmlBody = body?.html ?: "",
                    plainBody = body?.plain ?: "",
                )
            }
            return DaisyBook(title = ncc.title, author = ncc.author, chapters = chapters)
        } catch (t: Throwable) {
            if (t is DaisyParseException) throw t
            throw DaisyParseException("Failed to parse DAISY 2.02 package: ${t.message}", t)
        }
    }

    /** ncc.html → metadata + ordered heading list (page spans skipped). */
    private fun parseNcc(xml: String): NccDoc {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var title: String? = null
        var author: String? = null
        val headings = mutableListOf<NccHeading>()

        var inHeading = false
        var headingId: String? = null
        var headingHref: String? = null
        val titleBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase()
                    when {
                        name == "meta" -> {
                            val n = parser.getAttributeValue(null, "name")
                            val c = parser.getAttributeValue(null, "content")
                            if (c != null) when (n) {
                                "dc:title", "dc:Title" -> if (title == null) title = c.collapseWhitespace()
                                "dc:creator", "dc:Creator" -> if (author == null) author = c.collapseWhitespace()
                            }
                        }
                        name in HEADING_TAGS -> {
                            inHeading = true
                            headingId = parser.getAttributeValue(null, "id")
                            headingHref = null
                            titleBuf.setLength(0)
                        }
                        inHeading && name == "a" && headingHref == null ->
                            headingHref = parser.getAttributeValue(null, "href")
                    }
                }
                XmlPullParser.TEXT -> if (inHeading) titleBuf.append(parser.text ?: "")
                XmlPullParser.END_TAG -> {
                    val name = parser.name?.lowercase()
                    if (name in HEADING_TAGS && inHeading) {
                        val href = headingHref
                        if (href != null) {
                            headings.add(
                                NccHeading(
                                    id = headingId ?: "ncc-${headings.size + 1}",
                                    title = titleBuf.toString().collapseWhitespace(),
                                    href = href,
                                ),
                            )
                        }
                        inHeading = false
                    }
                }
            }
            event = parser.next()
        }
        return NccDoc(title, author, headings)
    }

    /**
     * In a SMIL doc, resolve a fragment id (from an ncc `href="file.smil#frag"`)
     * to the content `src` it points at.
     *
     * The DAISY 2.02 spec requires the destination anchor to reside within a SMIL
     * `<par>` **or** a `<text>` element — and **recommends the `<par>` form**. So
     * `frag` may be either:
     *  - a `<text id=frag src="…">` → return its `src` directly, or
     *  - a `<par id=frag>` → descend to that par's first `<text>` child and return
     *    its `src` (the spec says the text should be the par's first element).
     *
     * Handling only the `<text>` case silently produced empty chapter bodies for
     * every `<par>`-targeted book (the recommended, and very common, form). The
     * bundled WIPO sample happens to use `<text>` targets, which masked the gap.
     */
    private fun findContentSrc(xml: String, frag: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var inTargetPar = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                    "text" ->
                        // Direct <text id=frag>, or the first <text> inside a matched <par>.
                        if (inTargetPar || parser.getAttributeValue(null, "id") == frag) {
                            return parser.getAttributeValue(null, "src")
                        }
                    "par" ->
                        if (parser.getAttributeValue(null, "id") == frag) inTargetPar = true
                }
                XmlPullParser.END_TAG ->
                    // Matched par closed before yielding a <text> → stop treating it as the target.
                    if (inTargetPar && parser.name?.lowercase() == "par") inTargetPar = false
            }
            event = parser.next()
        }
        return null
    }

    /** Walk a content document, accumulating per-chapter bodies keyed by heading id. */
    private fun walkContent(
        xml: String,
        fragToHeadingId: Map<String, String>,
        out: MutableMap<String, ChapterBody>,
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var currentHeadingId: String? = null
        val paraBuf = StringBuilder()
        val htmlParas = mutableListOf<String>()
        val plainParas = mutableListOf<String>()
        var skipDepth = 0

        fun flushPara() {
            val block = paraBuf.toString().collapseWhitespace()
            if (block.isNotEmpty()) {
                htmlParas.add("<p>${block.escapeHtml()}</p>")
                plainParas.add(block)
            }
            paraBuf.setLength(0)
        }
        fun storeCurrent() {
            flushPara()
            val id = currentHeadingId ?: return
            if (htmlParas.isNotEmpty()) {
                out[id] = ChapterBody(htmlParas.joinToString("\n"), plainParas.joinToString("\n\n"))
            }
            htmlParas.clear()
            plainParas.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase()
                    val id = parser.getAttributeValue(null, "id")
                    val cls = parser.getAttributeValue(null, "class")?.lowercase() ?: ""
                    if (id != null && fragToHeadingId.containsKey(id)) {
                        storeCurrent()                       // finish the previous chapter
                        currentHeadingId = fragToHeadingId[id]
                        skipDepth = 0
                    }
                    if (cls.contains("page-") || name in SKIP_TEXT_TAGS) skipDepth++
                    if (name in BLOCK_TAGS) flushPara()
                }
                XmlPullParser.TEXT ->
                    if (currentHeadingId != null && skipDepth == 0) paraBuf.append(parser.text ?: "")
                XmlPullParser.END_TAG -> {
                    val name = parser.name?.lowercase()
                    val cls = parser.getAttributeValue(null, "class")?.lowercase() ?: ""
                    if (name in BLOCK_TAGS) flushPara()
                    if ((cls.contains("page-") || name in SKIP_TEXT_TAGS) && skipDepth > 0) skipDepth--
                }
            }
            event = parser.next()
        }
        storeCurrent()                                       // final chapter
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun readAllEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** Resolve a package-relative href against the package's base dir. */
    private fun resolveKey(entries: Map<String, ByteArray>, baseDir: String, name: String): String? {
        if (name.isEmpty()) return null
        val candidate = if (baseDir.isEmpty()) name else "$baseDir/$name"
        return entries.keys.firstOrNull { it == candidate }
            ?: entries.keys.firstOrNull { it.equals(candidate, true) }
            ?: entries.keys.firstOrNull { it.substringAfterLast('/').equals(name.substringAfterLast('/'), true) }
    }

    private val NAMED_ENTITY = Regex("&([a-zA-Z][a-zA-Z0-9]*);")
    private val PREDEFINED = setOf("amp", "lt", "gt", "quot", "apos")

    /**
     * Common HTML named entities → their literal character. DAISY 2.02 content is
     * XHTML and uses these freely, but Android's strict XML pull-parser only knows
     * the five predefined XML entities — a bare `&mdash;` throws. We pre-resolve
     * the common HTML entities to their actual characters (so `caf&eacute;` stays
     * "café" instead of collapsing to "caf "), keep the five predefined ones as
     * entities for the parser to decode, and fall back to a space for anything
     * unmapped (safe: still never breaks the parse). Numeric refs (`&#8212;`,
     * `&#x2014;`) are valid XML and pass through untouched for the parser.
     */
    private val HTML_ENTITIES: Map<String, String> = mapOf(
        // spacing / dashes / typographic punctuation
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "shy" to "",
        "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "middot" to "·", "bull" to "•",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
        "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
        "prime" to "′", "Prime" to "″", "dagger" to "†", "Dagger" to "‡",
        "permil" to "‰",
        // currency / symbols
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "plusmn" to "±", "times" to "×", "divide" to "÷", "minus" to "−",
        "frac14" to "¼", "frac12" to "½", "frac34" to "¾",
        "sup1" to "¹", "sup2" to "²", "sup3" to "³",
        "sect" to "§", "para" to "¶", "micro" to "µ",
        "cent" to "¢", "pound" to "£", "yen" to "¥", "euro" to "€", "curren" to "¤",
        "iexcl" to "¡", "iquest" to "¿", "brvbar" to "¦", "not" to "¬",
        "acute" to "´", "cedil" to "¸", "uml" to "¨", "macr" to "¯",
        "ordf" to "ª", "ordm" to "º",
        // Latin-1 accented letters (upper)
        "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã",
        "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç",
        "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë",
        "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î", "Iuml" to "Ï",
        "ETH" to "Ð", "Ntilde" to "Ñ",
        "Ograve" to "Ò", "Oacute" to "Ó", "Ocirc" to "Ô", "Otilde" to "Õ",
        "Ouml" to "Ö", "Oslash" to "Ø",
        "Ugrave" to "Ù", "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü",
        "Yacute" to "Ý", "THORN" to "Þ", "szlig" to "ß",
        // Latin-1 accented letters (lower)
        "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
        "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç",
        "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
        "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï",
        "eth" to "ð", "ntilde" to "ñ",
        "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ",
        "ouml" to "ö", "oslash" to "ø",
        "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü",
        "yacute" to "ý", "thorn" to "þ", "yuml" to "ÿ",
        // Latin Extended-A + HTML "special" letters (ligatures, carons, …) —
        // common in French / Slavic / Baltic names and text.
        "OElig" to "Œ", "oelig" to "œ",
        "Scaron" to "Š", "scaron" to "š",
        "Zcaron" to "Ž", "zcaron" to "ž",
        "Yuml" to "Ÿ", "fnof" to "ƒ",
        "circ" to "ˆ", "tilde" to "˜",
    )

    /** Decode UTF-8, then resolve named HTML entities so the strict XML pull-parser
     *  doesn't throw on (or silently lose) XHTML's `&nbsp;` / `&mdash;` / `&eacute;`
     *  etc. The five predefined XML entities are kept for the parser to decode;
     *  known HTML entities become their literal character; unknown named entities
     *  fall back to a space (never breaks the parse). */
    private fun decode(bytes: ByteArray): String =
        NAMED_ENTITY.replace(String(bytes, Charsets.UTF_8)) { m ->
            val name = m.groupValues[1]
            when {
                name in PREDEFINED -> m.value           // keep for the XML parser to decode
                else -> HTML_ENTITIES[name] ?: " "      // resolve known HTML entities; space-fallback unknowns
            }
        }

    private fun String.collapseWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
