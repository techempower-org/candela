package `in`.jphe.storyvox.source.github.render

import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.text.TextContentRenderer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Render a markdown chapter body into a [ChapterContent] (sanitized
 * HTML for the reader view + plaintext for the TTS engine).
 *
 * Two passes over the same parsed AST:
 *  1. [HtmlRenderer] → `htmlBody` for the reader.
 *  2. [TextContentRenderer] → `plainBody` for sentence chunking +
 *     audiobook playback. Default config is "compact" — paragraphs
 *     join with newlines, list items get a leading dash, headings
 *     and inline emphasis become bare text. Good enough for the
 *     SentenceChunker downstream.
 *
 * The `# Heading` first-line of a chapter file is stripped from
 * `plainBody` when present — `ManifestChapter.title` already carries
 * it, and reading the title aloud in the audio is redundant. (The
 * HTML view keeps the heading; that's a visual landmark for sighted
 * readers.)
 *
 * Honeypot enforcement (per spec line 130) is **deliberately not
 * applied here** — this is a pure markdown→content transform. The
 * caller (step 3d-detail-and-chapter, when GitHubSource.chapter
 * lights up) is the right layer to drop honeypot blocks before
 * handing to the playback engine, since that layer also has access
 * to the manifest's `honeypotSelectors` config.
 */
@Singleton
internal class MarkdownChapterRenderer @Inject constructor() {

    private val parser: Parser = Parser.builder().build()
    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .softbreak("<br />\n")
        .build()
    private val textRenderer: TextContentRenderer = TextContentRenderer.builder().build()

    /**
     * @param info chapter metadata to attach to the rendered content.
     * @param markdown raw chapter body. Empty or whitespace-only input
     *   yields an empty-but-valid [ChapterContent] (the reader UI
     *   handles the empty-body case).
     * @param stripLeadingTitle drop a heading-shaped first line from the
     *   plain text (see [stripLeadingHeading]). Pass false when that line
     *   is NOT the chapter title, e.g. an inbox Overview chapter whose first
     *   line is the pull request's title.
     */
    fun render(
        info: ChapterInfo,
        markdown: String,
        stripLeadingTitle: Boolean = true,
    ): ChapterContent {
        val htmlAst: Node = parser.parse(markdown)
        val html = htmlRenderer.render(htmlAst).trim()
        // Re-parse for plaintext so the AST mutation below doesn't
        // affect the HTML branch — Node visitors mutate the tree.
        val plainAst: Node = parser.parse(markdown)
        flattenLinksAndImages(plainAst)
        val plain = textRenderer.render(plainAst).trim()
        return ChapterContent(
            info = info,
            htmlBody = html,
            plainBody = if (stripLeadingTitle) stripLeadingHeading(plain) else plain,
        )
    }

    /**
     * Replace [Link] nodes with their child text and drop [Image]
     * nodes entirely from the AST. commonmark's TextContentRenderer
     * defaults to emitting `"anchor" (url)` for links and
     * `"alt" (src)` for images — fine for terminal logs, wrong for
     * audiobook playback. This visitor strips the URL/src half.
     *
     * Images go entirely (alt text often duplicates surrounding
     * prose); link anchor text stays since dropping it would orphan
     * the surrounding sentence ("Visit for more info." reads worse
     * than "Visit the docs for more info.").
     */
    private fun flattenLinksAndImages(root: Node) {
        root.accept(object : AbstractVisitor() {
            override fun visit(link: Link) {
                // Inline anchor children before dropping the Link wrapper.
                var child = link.firstChild
                while (child != null) {
                    val next = child.next
                    link.insertBefore(child)
                    child = next
                }
                link.unlink()
            }

            override fun visit(image: Image) {
                // Drop entirely — alt text is for sighted-fallback,
                // not for inserting into prose.
                image.unlink()
            }
        })
    }

    /**
     * Drop the first line if it's the chapter title repeated as a
     * heading — `ManifestChapter.title` already carries it. Match is
     * structural (the leading line is shorter than the body and ends
     * cleanly) rather than string-equality against the title; the
     * caller doesn't pass the title in, and false-positive trims on
     * a body whose first line happens to be heading-shaped are
     * acceptable for an audio renderer.
     */
    private fun stripLeadingHeading(plain: String): String {
        if (plain.isEmpty()) return plain
        val newline = plain.indexOf('\n')
        if (newline < 0) return plain
        val first = plain.substring(0, newline).trim()
        val rest = plain.substring(newline + 1).trimStart()
        // Only treat short, terminal-punctuation-free first lines as
        // headings. A normal opening sentence like "It was a dark and
        // stormy night." stays untouched because of the period.
        val isHeadingShape = first.length <= 80 &&
            first.isNotEmpty() &&
            first.last() !in TERMINAL_PUNCT &&
            rest.isNotEmpty()
        return if (isHeadingShape) rest else plain
    }

    private companion object {
        private val TERMINAL_PUNCT = setOf('.', '!', '?', '"', '”')
    }
}
