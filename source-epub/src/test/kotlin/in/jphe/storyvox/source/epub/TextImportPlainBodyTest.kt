package `in`.jphe.storyvox.source.epub

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.epub.config.EpubConfig
import `in`.jphe.storyvox.source.epub.config.EpubEntryKind
import `in`.jphe.storyvox.source.epub.config.EpubFileEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1619 — importing a `.txt` used to strip ALL line breaks: a
 * script / verse / teleprompter file collapsed into one run-on blob.
 *
 * Root cause: [EpubSource.chapter] derived `plainBody` by running the
 * `<pre>`-wrapped htmlBody through the tag-stripper, whose `\s+`→" "
 * collapse flattened every `\n`. The reader, the TTS `SentenceChunker`,
 * and paragraph-level navigation all consume `plainBody`, and paragraph
 * nav infers breaks from blank lines (`\n\n`) in it — so the collapse
 * silently killed paragraph nav for imported text too.
 *
 * Fix: the plaintext-import path now sets [EpubChapter.plainBody] straight
 * from the raw file via [normalizePlainText]; real EPUB chapters leave it
 * null and keep the byte-for-byte stripHtml behaviour.
 *
 * These tests drive the real render path end-to-end
 * ([EpubSource.fictionDetail] → [EpubSource.chapter]) so they assert what
 * the reader/engine actually receive, plus the pure [normalizePlainText]
 * contract. Paragraph-break counting here mirrors the blank-line rule that
 * `ParagraphBoundariesTest` (core-playback) pins for the nav layer itself.
 */
class TextImportPlainBodyTest {

    private class FakeEpubConfig(
        private val entry: EpubFileEntry,
        private val bytes: ByteArray,
    ) : EpubConfig {
        override val folderUriString: Flow<String?> = flowOf("folder://test")
        override suspend fun snapshot(): String? = "folder://test"
        override val books: Flow<List<EpubFileEntry>> = flowOf(listOf(entry))
        override suspend fun books(): List<EpubFileEntry> = listOf(entry)
        override suspend fun readBookBytes(uriString: String): ByteArray? =
            if (uriString == entry.uriString) bytes else null
    }

    /** Imports [raw] as a plaintext file and returns the resulting
     *  chapter's `plainBody` — the exact string the reader and TTS read. */
    private fun importedPlainBody(displayName: String, raw: String): String = runBlocking {
        val fictionId = "epub:txt:test"
        val entry = EpubFileEntry(
            fictionId = fictionId,
            uriString = "uri://$displayName",
            displayName = displayName,
            kind = EpubEntryKind.Text,
        )
        val source = EpubSource(FakeEpubConfig(entry, raw.toByteArray(Charsets.UTF_8)))

        val detail = source.fictionDetail(fictionId)
        assertTrue("fictionDetail should succeed", detail is FictionResult.Success)
        val chapterId = (detail as FictionResult.Success).value.chapters.single().id

        val content = source.chapter(fictionId, chapterId)
        assertTrue("chapter should succeed", content is FictionResult.Success)
        (content as FictionResult.Success).value.plainBody
    }

    /** Count blank-line-separated paragraphs — the same signal
     *  `paragraphHeadIndices` keys on. */
    private fun paragraphCount(body: String): Int =
        body.split(Regex("\n[ \t]*\n")).count { it.isNotBlank() }

    // ── end-to-end: the reported bug ────────────────────────────────

    @Test
    fun `blank-line paragraphs survive import as paragraph breaks`() {
        val script = "TITLE CARD\n\nFADE IN:\n\nINT. ROOM - DAY\n\nBob waves."
        val body = importedPlainBody("scene.txt", script)

        assertTrue("paragraph breaks (\\n\\n) must survive", body.contains("\n\n"))
        assertEquals("all four paragraphs preserved", 4, paragraphCount(body))
    }

    @Test
    fun `hard line breaks within a paragraph survive import`() {
        // A soft line break (single \n) inside one paragraph — verse /
        // stage directions rely on these. Pre-fix this became a space.
        val verse = "Roses are red,\nViolets are blue,\nSugar is sweet."
        val body = importedPlainBody("poem.txt", verse)

        assertEquals("verse kept as three lines", verse, body)
        assertTrue(body.contains("red,\nViolets"))
        assertFalse("newlines must NOT collapse to spaces", body.contains("red, Violets"))
    }

    @Test
    fun `whole file no longer collapses to a single line`() {
        val raw = "Line one.\nLine two.\n\nNew paragraph."
        val body = importedPlainBody("notes.txt", raw)
        // The pre-fix output was "Line one. Line two. New paragraph."
        assertTrue("must contain at least one newline", body.contains("\n"))
        assertFalse(
            "must not be the old single-line collapse",
            body == "Line one. Line two. New paragraph.",
        )
    }

    // ── end-to-end: latent entity-leak bug fixed alongside ──────────

    @Test
    fun `ampersand and angle brackets stay literal, not HTML entities`() {
        // The old path escaped these into a <pre> block then stripped tags
        // WITHOUT decoding, leaking literal "&amp;" / "&lt;" into the prose.
        val raw = "Tom & Jerry\n\nif x < y > z"
        val body = importedPlainBody("chars.txt", raw)

        assertTrue(body.contains("Tom & Jerry"))
        assertTrue(body.contains("if x < y > z"))
        assertFalse("no &amp; leak", body.contains("&amp;"))
        assertFalse("no &lt; leak", body.contains("&lt;"))
        assertFalse("no &gt; leak", body.contains("&gt;"))
    }

    // ── normalizePlainText: pure contract ───────────────────────────

    @Test
    fun `single newline is a soft wrap and is preserved`() {
        assertEquals("a\nb", normalizePlainText("a\nb"))
    }

    @Test
    fun `crlf and lone cr normalize to lf`() {
        assertEquals("a\n\nb", normalizePlainText("a\r\n\r\nb"))
        assertEquals("a\nb", normalizePlainText("a\rb"))
    }

    @Test
    fun `runs of three or more newlines collapse to one blank line`() {
        assertEquals("a\n\nb", normalizePlainText("a\n\n\n\nb"))
    }

    @Test
    fun `leading and trailing blank lines are trimmed`() {
        assertEquals("body", normalizePlainText("\n\n\nbody\n\n\n"))
    }

    @Test
    fun `trailing horizontal whitespace is trimmed but indentation is kept`() {
        // Leading indent is authorial intent (verse / script) → keep it.
        // Trailing spaces/tabs are noise → drop them.
        assertEquals("    indented\nnext", normalizePlainText("    indented   \nnext\t"))
    }

    @Test
    fun `interior horizontal whitespace inside a line is preserved`() {
        // Aligned columns in a script must not be collapsed the way an
        // HTML stripper would.
        assertEquals("NAME:    Bob", normalizePlainText("NAME:    Bob"))
    }
}
