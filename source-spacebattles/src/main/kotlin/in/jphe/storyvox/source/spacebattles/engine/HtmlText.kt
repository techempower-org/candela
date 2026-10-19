package `in`.jphe.storyvox.source.spacebattles.engine

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * TTS-friendly HTML → plain text. Unlike `Element.text()` (which flattens
 * every `<br>` and block boundary into a single space) this keeps paragraph
 * structure: block elements become blank-line separated paragraphs and
 * `<br>` becomes a newline — so the sentence chunker sees real boundaries
 * on forum posts that format with `<br><br>` instead of `<p>`.
 */
internal object HtmlText {

    private val DROP = "img, hr, iframe, script, style, noscript, button, select, form, svg, ins"
    private val BLOCKS = "p, div, h1, h2, h3, h4, h5, h6, li, blockquote, pre, tr, center"

    internal fun plain(root: Element): String {
        val c = root.clone()
        c.select(DROP).remove()
        // Normalise whitespace INSIDE text nodes first, so source-formatting
        // newlines/indentation never masquerade as paragraph breaks.
        val texts = mutableListOf<TextNode>()
        c.traverse { node: Node, _: Int -> if (node is TextNode) texts += node }
        texts.forEach { it.text(it.text().replace(' ', ' ')) }
        c.select("br").forEach { it.replaceWith(TextNode("\n")) }
        c.select(BLOCKS).filter { it !== c }.forEach {
            it.before(TextNode("\n\n"))
            it.after(TextNode("\n\n"))
        }
        return c.wholeText()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
