package `in`.jphe.storyvox.source.bookshare.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Issue #1002 — minimal DAISY 3 **DTBook** text extractor.
 *
 * Bookshare (and other accessible-library) titles arrive as DAISY. For a
 * text-to-speech app we only need the TEXT — we re-narrate with our own
 * voices, so DAISY's bundled audio + SMIL timing are irrelevant. This parser
 * reads a DAISY 3 DTBook XML document (`http://www.daisy.org/z3986/2005/dtbook/`)
 * and flattens it into ordered chapters (title + simple HTML body + plaintext
 * body) — the same (title, htmlBody, plainBody) shape the rest of the source
 * layer consumes.
 *
 * ## Text capture model
 *
 * Each top-level `<level1>` / `<level>` becomes one chapter; its first heading
 * (`<h1>`..`<h6>` / `<hd>`) is the title. For the body we **capture all text
 * inside the chapter** and split it into paragraphs at known **block-level**
 * boundaries ([BLOCK_TAGS] — `p`, list items, blockquotes, verse lines, notes,
 * table cells, …). Anything not in that set is treated as *inline*, so its text
 * flows into the surrounding paragraph — which means an unrecognised element
 * never silently drops content (worst case two paragraphs merge). Page-turn and
 * note-reference markers ([SKIP_TEXT_TAGS]) are dropped so they don't interrupt
 * narration. Uses only Android's bundled `XmlPullParser` (no third-party XML
 * dep), mirroring [`EpubParser`][in.jphe.storyvox.source.epub.parse.EpubParser].
 *
 * Deliberately NOT handled in this scaffold (tracked as #1293 / partnership):
 * DAISY 2.02 `ncc.html` navigation + its separate content docs, the zip
 * package/manifest walk, MathML/tables-as-structure, and **Protected DAISY
 * (PDTB) decryption** (Bookshare copyrighted downloads — gated on the #1002
 * partnership).
 */
object DaisyParser {

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6", "hd")
    private val CHAPTER_TAGS = setOf("level1", "level")

    /**
     * Block-level elements that delimit a paragraph. Containers (list, table,
     * linegroup, …) are included too — flushing an already-empty buffer is a
     * no-op, so listing them is harmless and future-proofs odd nestings.
     * Anything NOT here is treated as inline (its text flows into the current
     * paragraph), so DTBook inline markup like `<sent>` / `<w>` / `<em>` keeps
     * a paragraph intact instead of shattering it.
     */
    private val BLOCK_TAGS = setOf(
        "p", "list", "li", "dl", "dt", "dd", "blockquote", "note", "sidebar",
        "prodnote", "table", "tr", "td", "th", "caption", "linegroup", "line",
        "poem", "byline", "dateline", "epigraph", "author", "div", "bridgehead",
    ) + HEADING_TAGS

    /** Elements whose text is structural, not narratable (page-turn + note markers). */
    private val SKIP_TEXT_TAGS = setOf("pagenum", "noteref", "linenum")

    /** Parse a DAISY 3 DTBook XML document into a [DaisyBook]. */
    fun parseDtbook(xml: String): DaisyBook {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var bookTitle: String? = null
            var bookAuthor: String? = null
            val chapters = mutableListOf<DaisyChapter>()

            var chapterDepth = 0            // >0 while inside a top-level section
            var chapterId: String? = null
            var chapterTitle: String? = null
            var titleTaken = false
            var capturingTitle = false
            val titleBuf = StringBuilder()
            val paraBuf = StringBuilder()
            val htmlParas = mutableListOf<String>()
            val plainParas = mutableListOf<String>()
            var skipDepth = 0               // >0 while inside a non-narratable marker
            var chapterIndex = 0

            fun flushPara() {
                val block = paraBuf.toString().collapseWhitespace()
                if (block.isNotEmpty()) {
                    htmlParas.add("<p>${block.escapeHtml()}</p>")
                    plainParas.add(block)
                }
                paraBuf.setLength(0)
            }

            fun resetChapter() {
                chapterId = null
                chapterTitle = null
                titleTaken = false
                capturingTitle = false
                titleBuf.setLength(0)
                paraBuf.setLength(0)
                htmlParas.clear()
                plainParas.clear()
                skipDepth = 0
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase()
                        when {
                            name == "meta" -> {
                                val metaName = parser.getAttributeValue(null, "name")
                                val content = parser.getAttributeValue(null, "content")
                                if (content != null) when (metaName) {
                                    "dc:Title", "dc:title" -> if (bookTitle == null) bookTitle = content.collapseWhitespace()
                                    "dc:Creator", "dc:creator" -> if (bookAuthor == null) bookAuthor = content.collapseWhitespace()
                                }
                            }
                            name in CHAPTER_TAGS -> {
                                if (chapterDepth == 0) {
                                    resetChapter()
                                    chapterId = parser.getAttributeValue(null, "id")
                                }
                                chapterDepth++
                            }
                            chapterDepth > 0 && name in SKIP_TEXT_TAGS -> skipDepth++
                            chapterDepth > 0 && name in HEADING_TAGS && !titleTaken -> {
                                flushPara()                 // close any pre-heading prose
                                capturingTitle = true
                                titleBuf.setLength(0)
                            }
                            chapterDepth > 0 && name in BLOCK_TAGS -> flushPara()
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (chapterDepth > 0 && skipDepth == 0) {
                            val text = parser.text ?: ""
                            if (capturingTitle) titleBuf.append(text) else paraBuf.append(text)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase()
                        when {
                            chapterDepth > 0 && name in SKIP_TEXT_TAGS && skipDepth > 0 -> skipDepth--
                            capturingTitle && name in HEADING_TAGS -> {
                                chapterTitle = titleBuf.toString().collapseWhitespace()
                                capturingTitle = false
                                titleTaken = true
                                titleBuf.setLength(0)
                            }
                            chapterDepth > 0 && name in BLOCK_TAGS -> flushPara()
                            name in CHAPTER_TAGS && chapterDepth > 0 -> {
                                chapterDepth--
                                if (chapterDepth == 0) {
                                    flushPara()             // final paragraph
                                    chapterIndex++
                                    val title = chapterTitle?.takeIf { it.isNotEmpty() }
                                        ?: "Section $chapterIndex"
                                    chapters.add(
                                        DaisyChapter(
                                            id = chapterId ?: "level-$chapterIndex",
                                            title = title,
                                            htmlBody = htmlParas.joinToString("\n"),
                                            plainBody = plainParas.joinToString("\n\n"),
                                        ),
                                    )
                                    resetChapter()
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }

            return DaisyBook(title = bookTitle, author = bookAuthor, chapters = chapters)
        } catch (t: Throwable) {
            throw DaisyParseException("Failed to parse DTBook XML: ${t.message}", t)
        }
    }

    private fun String.collapseWhitespace(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
