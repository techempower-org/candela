package `in`.jphe.storyvox.source.librivox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1224 — unit coverage for [GutenbergChapterSplitter], the pure
 * Gutenberg-text → per-LibriVox-section splitter behind read-along.
 *
 * Covers the two strategies (heading-aligned, proportional), the
 * table-of-contents filter, the char-split last resort, and the
 * always-exactly-N / no-text-lost guarantees.
 */
class GutenbergChapterSplitterTest {

    /** A chapter body comfortably above the splitter's MIN_BODY_GAP (400
     *  chars) so its preceding heading survives the TOC filter. */
    private fun body(marker: String): String {
        val sentence = "$marker — and the wind moved slow across the long grey field that morning. "
        return buildString { repeat(8) { append(sentence) } }.trim()
    }

    @Test fun `non-positive section count yields empty list`() {
        assertEquals(emptyList<String>(), GutenbergChapterSplitter.split("anything", 0))
        assertEquals(emptyList<String>(), GutenbergChapterSplitter.split("anything", -3))
    }

    @Test fun `single section gets the whole text`() {
        val text = "Once upon a time.\n\nThe end."
        assertEquals(listOf(text), GutenbergChapterSplitter.split(text, 1))
    }

    @Test fun `empty text yields n empty segments`() {
        val result = GutenbergChapterSplitter.split("   \n  \n ", 3)
        assertEquals(3, result.size)
        assertTrue(result.all { it.isEmpty() })
    }

    @Test fun `headings matching section count split at chapter boundaries`() {
        val text = buildString {
            append("A short preface before the story begins.\n\n")
            append("CHAPTER I\n\n").append(body("BODYONE")).append("\n\n")
            append("CHAPTER II\n\n").append(body("BODYTWO")).append("\n\n")
            append("CHAPTER III\n\n").append(body("BODYTHREE"))
        }
        val segs = GutenbergChapterSplitter.split(text, 3)

        assertEquals(3, segs.size)
        // Front matter rides along with the first chapter.
        assertTrue(segs[0].contains("A short preface"))
        assertTrue(segs[0].contains("CHAPTER I"))
        assertTrue(segs[0].contains("BODYONE"))
        assertTrue(segs[1].startsWith("CHAPTER II"))
        assertTrue(segs[1].contains("BODYTWO"))
        assertTrue(segs[2].contains("CHAPTER III"))
        assertTrue(segs[2].contains("BODYTHREE"))
        // Boundaries are clean — chapter 2's segment doesn't bleed into 3.
        assertFalse(segs[1].contains("BODYTHREE"))
        assertFalse(segs[0].contains("BODYTWO"))
    }

    @Test fun `lone roman numeral headings split`() {
        val text = buildString {
            append("I\n\n").append(body("ALPHA")).append("\n\n")
            append("II\n\n").append(body("BETA")).append("\n\n")
            append("III\n\n").append(body("GAMMA"))
        }
        val segs = GutenbergChapterSplitter.split(text, 3)
        assertEquals(3, segs.size)
        assertTrue(segs[0].contains("ALPHA"))
        assertTrue(segs[1].contains("BETA"))
        assertTrue(segs[2].contains("GAMMA"))
    }

    @Test fun `table-of-contents entries are filtered out`() {
        // A packed TOC block, immediately followed by the real chapters.
        // The TOC headings sit a line apart (< MIN_BODY_GAP) so they're
        // dropped; only the three body-backed headings survive → a clean
        // 3-way split.
        val text = buildString {
            append("CONTENTS\n")
            append("CHAPTER I\n")
            append("CHAPTER II\n")
            append("CHAPTER III\n")
            append("CHAPTER I\n\n").append(body("REALONE")).append("\n\n")
            append("CHAPTER II\n\n").append(body("REALTWO")).append("\n\n")
            append("CHAPTER III\n\n").append(body("REALTHREE"))
        }
        val segs = GutenbergChapterSplitter.split(text, 3)
        assertEquals(3, segs.size)
        assertTrue(segs[1].contains("REALTWO"))
        assertTrue(segs[2].contains("REALTHREE"))
        // The real chapter 2 starts at its real heading, not a TOC line.
        assertFalse(segs[2].contains("REALTWO"))
    }

    @Test fun `heading count mismatch falls back to proportional and stays exactly n`() {
        // Two headings, but three sections — heading split can't align, so
        // proportional takes over and still yields exactly three segments.
        val text = buildString {
            append("CHAPTER I\n\n").append(body("ONE")).append("\n\n")
            append("CHAPTER II\n\n").append(body("TWO"))
        }
        val segs = GutenbergChapterSplitter.split(text, 3)
        assertEquals(3, segs.size)
        assertTrue(segs.all { it.isNotEmpty() })
    }

    @Test fun `proportional split preserves every paragraph in order and loses no text`() {
        val paras = (1..9).map { "Paragraph number $it with several words to count here." }
        val text = paras.joinToString("\n\n")
        val segs = GutenbergChapterSplitter.split(text, 3)

        assertEquals(3, segs.size)
        assertTrue(segs.all { it.isNotEmpty() })
        // Every paragraph survives exactly once, in order.
        val rejoined = segs.joinToString("\n\n")
        for (p in paras) assertTrue("missing: $p", rejoined.contains(p))
        // Order preserved: paragraph 1 before 9 in the concatenation.
        assertTrue(rejoined.indexOf("number 1 ") < rejoined.indexOf("number 9 "))
    }

    @Test fun `fewer paragraphs than sections uses char split covering all words`() {
        // One un-delimited blob, three sections — no paragraph boundaries,
        // so the char-split last resort runs.
        val text = (1..30).joinToString(" ") { "word$it" }
        val segs = GutenbergChapterSplitter.split(text, 3)

        assertEquals(3, segs.size)
        // Words aren't cut mid-token and none are lost.
        val rejoined = segs.joinToString(" ")
        for (i in 1..30) assertTrue("missing word$i", Regex("\\bword$i\\b").containsMatchIn(rejoined))
    }

    @Test fun `result size always equals section count`() {
        val text = buildString {
            append("CHAPTER I\n\n").append(body("X")).append("\n\n")
            append("CHAPTER II\n\n").append(body("Y"))
        }
        for (n in 1..6) {
            assertEquals("n=$n", n, GutenbergChapterSplitter.split(text, n).size)
        }
    }
}
