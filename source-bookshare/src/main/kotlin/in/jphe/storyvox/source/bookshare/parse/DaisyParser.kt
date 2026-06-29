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
 * Scope: each top-level `<level1>` / `<level>` becomes one chapter; its first
 * heading (`<h1>`..`<h6>` / `<hd>`) is the title and the remaining block text
 * (`<p>` + sub-headings) is the body. Book title/author come from the DTBook
 * `<head>` `dc:Title` / `dc:Creator` meta. Uses only Android's bundled
 * `XmlPullParser` (no third-party XML dep), mirroring
 * [`EpubParser`][in.jphe.storyvox.source.epub.parse.EpubParser]'s posture.
 *
 * Deliberately NOT handled in this scaffold (follow-ups): DAISY 2.02
 * `ncc.html` navigation + its separate content docs, the zip package/manifest
 * walk, MathML/tables, and — critically — **Protected DAISY (PDTB)
 * decryption**, which Bookshare copyrighted downloads require and which is
 * gated on the partner relationship (see the #1002 research comment).
 */
object DaisyParser {

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6", "hd")
    private val CHAPTER_TAGS = setOf("level1", "level")

    /** Parse a DAISY 3 DTBook XML document into a [DaisyBook]. */
    fun parseDtbook(xml: String): DaisyBook {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var bookTitle: String? = null
            var bookAuthor: String? = null
            val chapters = mutableListOf<DaisyChapter>()

            // Per-chapter accumulation.
            var chapterDepth = 0            // >0 while inside a top-level section
            var chapterId: String? = null
            var chapterTitle: String? = null
            var titleTaken = false
            var titleBuf: StringBuilder? = null   // capturing the chapter's first heading
            var blockBuf: StringBuilder? = null   // capturing a <p> / sub-heading block
            val htmlParas = mutableListOf<String>()
            val plainParas = mutableListOf<String>()
            var chapterIndex = 0

            fun resetChapter() {
                chapterId = null
                chapterTitle = null
                titleTaken = false
                titleBuf = null
                blockBuf = null
                htmlParas.clear()
                plainParas.clear()
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
                            chapterDepth > 0 && name in HEADING_TAGS && !titleTaken ->
                                titleBuf = StringBuilder()
                            chapterDepth > 0 && (name == "p" || (name in HEADING_TAGS && titleTaken)) ->
                                blockBuf = StringBuilder()
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text ?: ""
                        titleBuf?.append(text)
                        blockBuf?.append(text)
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase()
                        when {
                            titleBuf != null && name in HEADING_TAGS && !titleTaken -> {
                                chapterTitle = titleBuf!!.toString().collapseWhitespace()
                                titleBuf = null
                                titleTaken = true
                            }
                            blockBuf != null && (name == "p" || name in HEADING_TAGS) -> {
                                val block = blockBuf!!.toString().collapseWhitespace()
                                if (block.isNotEmpty()) {
                                    htmlParas.add("<p>${block.escapeHtml()}</p>")
                                    plainParas.add(block)
                                }
                                blockBuf = null
                            }
                            name in CHAPTER_TAGS && chapterDepth > 0 -> {
                                chapterDepth--
                                if (chapterDepth == 0) {
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
