package `in`.jphe.storyvox.data.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

/**
 * Issue #1628 — the single, shared HTML → plaintext conversion for chapter /
 * content bodies. Every source should use this instead of a hand-rolled
 * regex stripper.
 *
 * The reader, the TTS `SentenceChunker`, and paragraph-level navigation all
 * consume `ChapterContent.plainBody`, and paragraph-nav infers paragraph
 * breaks from blank lines (`\n\n`) in that string. A source that flattens
 * its HTML to a single line (the #1619 / #1623 / #1626 / #1627 class)
 * produces a run-on blob, silently breaks paragraph navigation, and — if it
 * doesn't decode entities — leaks `&amp;` / `&#8217;` / `&rsquo;` into the
 * reader and, worse, into TTS narration.
 *
 * Implementation: parse to a DOM (jsoup — robust on the malformed markup
 * real RSS feeds and scraped chapters emit, where regex strippers break),
 * drop non-content nodes, append a blank line after each block element (the
 * exact signal `paragraphHeadIndices` keys on) and a single `\n` for `<br>`,
 * and let jsoup decode entities natively (covering curly quotes, em-dashes,
 * numeric refs — which the per-source regex entity tables miss).
 *
 * Handles both full XHTML documents (EPUB chapter files, `<html><head>…`)
 * and bare fragments (RSS item bodies, scraped chapter HTML): jsoup wraps a
 * fragment in a `<body>`, and a fragment simply has no head/style to drop.
 *
 * Known limitation: whitespace inside `<pre>` is normalized like ordinary
 * prose (jsoup `TextNode.text()`); rare in prose content, a verbatim-`<pre>`
 * mode is a follow-up if a real book ever needs it.
 */
fun String.htmlToPlainText(): String {
    if (isBlank()) return ""
    val doc = Jsoup.parse(this)
    // Non-content: strip so their text never reaches the reader / narration.
    doc.select("head, script, style, noscript, svg, title").remove()
    val sb = StringBuilder()
    doc.body().traverse(object : NodeVisitor {
        override fun head(node: Node, depth: Int) {
            when {
                node is TextNode -> sb.append(node.text())
                node is Element && node.normalName() == "br" -> sb.append('\n')
            }
        }

        override fun tail(node: Node, depth: Int) {
            // Blank line after each block so paragraph-nav sees the break;
            // runs of block-closes collapse to one gap in normalization.
            if (node is Element && node.normalName() in HTML_BLOCK_TAGS) {
                sb.append("\n\n")
            }
        }
    })
    return sb.toString().normalizeBlockWhitespace()
}

/** Block-level tags whose close introduces a paragraph break. Inline tags
 *  (`<em>`, `<strong>`, `<a>`, …) are intentionally absent so their text
 *  stays on the same line. */
private val HTML_BLOCK_TAGS: Set<String> = setOf(
    "p", "div", "section", "article", "header", "footer", "aside", "main",
    "blockquote", "pre", "figure", "figcaption",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "li", "dl", "dt", "dd",
    "table", "tr", "hr",
)

/** Collapse horizontal whitespace, trim each line's edges, cap blank runs,
 *  and trim — while preserving `\n` (line) and `\n\n` (paragraph). */
private fun String.normalizeBlockWhitespace(): String =
    replace('\r', '\n')
        .replace(Regex("[ \\t\\x0B\\u000C]+"), " ")
        .replace(Regex(" *\n *"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
