package `in`.jphe.storyvox.source.mempalace.writeback

import `in`.jphe.storyvox.data.annotation.HighlightCapture
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1468 — the drawer a highlight becomes, and the request that carries it. */
class HighlightDrawerPayloadTest {

    private fun capture(
        quote: String = "It was a bright cold day in April.",
        note: String? = null,
        fictionTitle: String = "Nineteen",
        chapterTitle: String = "Chapter 1",
    ) = HighlightCapture(
        annotationId = "a1b2c3",
        fictionId = "royalroad:123",
        chapterId = "royalroad:123:4",
        fictionTitle = fictionTitle,
        chapterTitle = chapterTitle,
        quotedText = quote,
        note = note,
        startOffset = 120,
        endOffset = 245,
        createdAt = 1_790_000_000_000L,
    )

    @Test fun `content quotes the passage and carries titles and position`() {
        val c = HighlightDrawerPayload.content(capture())
        assertTrue(c.startsWith("> It was a bright cold day in April."))
        assertTrue(c.contains("— Nineteen, Chapter 1"))
        assertTrue(c.contains("Position: chars 120–245"))
        assertTrue(c.contains("fiction: royalroad:123 · chapter: royalroad:123:4 · highlight: a1b2c3"))
        assertTrue(c.contains("Highlighted in Candela · 2026-"))
    }

    @Test fun `note appears only when present`() {
        assertFalse(HighlightDrawerPayload.content(capture(note = null)).contains("Note:"))
        assertFalse(HighlightDrawerPayload.content(capture(note = "   ")).contains("Note:"))
        assertTrue(HighlightDrawerPayload.content(capture(note = "Orwell opener")).contains("Note: Orwell opener"))
    }

    @Test fun `multi-line quote stays one block quote`() {
        val c = HighlightDrawerPayload.content(capture(quote = "line one\n\nline three"))
        assertTrue(c.startsWith("> line one\n>\n> line three\n\n"))
    }

    @Test fun `blank chapter title omits the separator and blank fiction title falls back`() {
        val c = HighlightDrawerPayload.content(capture(fictionTitle = " ", chapterTitle = ""))
        assertTrue(c.contains("— Untitled\n"))
    }

    @Test fun `oversized quote and note are clamped under the WorkManager data budget`() {
        val c = HighlightDrawerPayload.content(
            capture(quote = "x".repeat(50_000), note = "n".repeat(50_000), fictionTitle = "t".repeat(5_000)),
        )
        assertTrue(c.length <= HighlightDrawerPayload.MAX_CONTENT_CHARS)
        // Worst case modified UTF-8 is 3 bytes per char; stay under 10 KB.
        assertTrue(c.length * 3 < 10 * 1024)
        assertTrue(c.contains("…"))
    }

    @Test fun `clamp leaves short strings alone and marks truncation`() {
        assertEquals("abc", HighlightDrawerPayload.clamp("abc", 10))
        val clamped = HighlightDrawerPayload.clamp("abcdefghij", 5)
        assertEquals(5, clamped.length)
        assertTrue(clamped.endsWith("…"))
    }

    @Test fun `request body is valid JSON with canonical wing and room and escaped content`() {
        val tricky = "He said \"stop\"\n\tand \\ left"
        val body = HighlightDrawerPayload.requestBody(tricky)
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals(tricky, obj["content"]!!.jsonPrimitive.content)
        assertEquals("candela_highlights", obj["wing"]!!.jsonPrimitive.content)
        assertEquals("references", obj["room"]!!.jsonPrimitive.content)
    }

    @Test fun `wing is already in palace-daemon normalised slug form`() {
        assertTrue(Regex("^[a-z0-9_]+$").matches(HighlightDrawerPayload.WING))
    }

    @Test fun `write-back is off by default and needs both a host and the opt-in`() {
        assertFalse(PalaceConfigState(host = "10.0.6.50:8085", apiKey = "").isHighlightWriteBackActive)
        assertFalse(PalaceConfigState(host = "", apiKey = "", highlightWriteBack = true).isHighlightWriteBackActive)
        assertTrue(
            PalaceConfigState(host = "10.0.6.50:8085", apiKey = "", highlightWriteBack = true)
                .isHighlightWriteBackActive,
        )
    }
}
