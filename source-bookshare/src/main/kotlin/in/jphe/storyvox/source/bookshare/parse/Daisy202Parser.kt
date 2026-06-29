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
 *  - `*.smil` — sync: `<par><text src="content.html#frag" id="txt_N"/></par>`.
 *    The heading's `#frag` is a SMIL `<text id>`, whose `src` points into a
 *    content document.
 *  - content `*.html` — the actual prose (text lives inside `<a>` link text),
 *    every block carrying an id.
 *
 * For a TTS app we only need the text (we re-narrate), so the algorithm is:
 *  1. parse `ncc.html` → ordered headings (title + `smil#frag`); skip page spans.
 *  2. per heading, follow `smil#frag` → its `<text src>` → the chapter's START
 *     content-document fragment id (one lookup, no full par-sequence walk).
 *  3. walk the content document ONCE, capturing all text (block boundaries split
 *     paragraphs; unknown tags are inline so text is never dropped; `page-*`
 *     class spans + `pagenum`/`noteref` are skipped), switching chapters when an
 *     element's id is a chapter-start id.
 *
 * Mirrors [`EpubParser`][in.jphe.storyvox.source.epub.parse.EpubParser]'s
 * XmlPullParser + zip-walk posture. Named non-predefined entities (`&nbsp;`,
 * `&mdash;`, …) are neutralised to spaces up front so XHTML content doesn't
 * throw on the strict XML pull-parser; the five predefined XML entities are
 * preserved. NOT handled: protected (PDTB) packages (the #1002 partnership).
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

    /** In a SMIL doc, find `<text id=[textId] src="…">` and return its `src`. */
    private fun findContentSrc(xml: String, textId: String): String? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.lowercase() == "text") {
                if (parser.getAttributeValue(null, "id") == textId) {
                    return parser.getAttributeValue(null, "src")
                }
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

    /** Decode UTF-8 + neutralise named non-predefined entities to spaces so the
     *  strict XML pull-parser doesn't throw on XHTML's `&nbsp;` etc. */
    private fun decode(bytes: ByteArray): String =
        NAMED_ENTITY.replace(String(bytes, Charsets.UTF_8)) { m ->
            if (m.groupValues[1] in PREDEFINED) m.value else " "
        }

    private fun String.collapseWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
