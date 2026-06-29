package `in`.jphe.storyvox.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Issue #1310 — extract plain text from an OpenDocument Text (`.odt`) file.
 *
 * An `.odt` is a ZIP archive whose `content.xml` holds the body in ODF
 * markup (`<office:text>` → `<text:p>` / `<text:h>` / `<text:span>` …).
 * This mirrors the EPUB import shape (ZIP + markup), but ODF is regular,
 * machine-generated XML, so a targeted strip is enough to recover the
 * narratable prose without pulling in a full XML stack.
 *
 * Scope (v1, per #1310): paragraphs, headings, spans, tabs, line breaks,
 * and runs of spaces (`<text:s text:c="N"/>`). Tables, footnotes, and
 * tracked-changes degrade gracefully to their text content rather than
 * erroring. The pure [odfXmlToText] seam is the unit-tested core; [extract]
 * adds only the ZIP read so the conversion is testable without a real file.
 */
object OdtTextExtractor {

    /** Extract plain text from raw `.odt` bytes; "" when there's no `content.xml`. */
    fun extract(odtBytes: ByteArray): String =
        readContentXml(odtBytes)?.let(::odfXmlToText).orEmpty()

    private fun readContentXml(odtBytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(odtBytes)).use { zip ->
            // ODF mandates `content.xml` at the archive root.
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name == "content.xml") {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return null
    }

    /**
     * Convert ODF `content.xml` markup to plain text. Pure (no I/O), so the
     * conversion rules are unit-tested directly off an XML string.
     */
    fun odfXmlToText(contentXml: String): String {
        // Scope to <office:text> so document styles / metadata never leak in.
        val body = OFFICE_TEXT.find(contentXml)?.groupValues?.getOrNull(1) ?: contentXml
        var s = body
        // Inline whitespace constructs → their literal characters.
        s = LINE_BREAK.replace(s, "\n")
        s = TAB.replace(s, "\t")
        s = SPACES.replace(s) { m ->
            " ".repeat((m.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 64))
        }
        // Paragraph / heading ends become blank-line separators.
        s = PARA_OR_HEADING_END.replace(s, "\n\n")
        // Drop every remaining tag, keeping the text content between tags.
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        // Tidy: trim trailing space per line, collapse 3+ newlines to one blank line.
        return s.lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .replace(THREE_PLUS_NEWLINES, "\n\n")
            .trim()
    }

    /** Decode the five predefined XML entities. `&amp;` is decoded LAST so a
     *  literal `&amp;lt;` round-trips to `&lt;` rather than collapsing to `<`. */
    private fun decodeEntities(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private val OFFICE_TEXT = Regex("""<office:text[^>]*>(.*)</office:text>""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_BREAK = Regex("""<text:line-break\s*/>""")
    private val TAB = Regex("""<text:tab\s*/>""")
    private val SPACES = Regex("""<text:s(?:\s+text:c="(\d+)")?\s*/>""")
    private val PARA_OR_HEADING_END = Regex("""</text:(?:p|h)>""")
    private val TAG = Regex("""<[^>]+>""")
    private val THREE_PLUS_NEWLINES = Regex("""\n{3,}""")
}
