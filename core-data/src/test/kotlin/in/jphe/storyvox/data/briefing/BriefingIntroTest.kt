package `in`.jphe.storyvox.data.briefing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1847 — the spoken intro before each briefing item: "From …" for the first,
 * "Next, from …" in the middle, "Finally, from …" for the last, with the
 * source id standing in when the registry has no label.
 */
class BriefingIntroTest {

    private val en = BriefingIntroTemplates(
        first = "From %1\$s: %2\$s.",
        next = "Next, from %1\$s: %2\$s.",
        last = "Finally, from %1\$s: %2\$s.",
    )
    private val labels = mapOf("hackernews" to "Hacker News", "rss" to "RSS")

    private fun item(source: String, title: String) =
        BriefingItem(fictionId = "$source:f", chapterId = "$source:c", sourceId = source, title = title)

    private fun intro(source: String, title: String, index: Int, total: Int) =
        briefingIntroFor(item(source, title), index, total, labels::get, en)

    @Test
    fun `the first item says From`() =
        assertEquals("From Hacker News: Show HN: a thing.", intro("hackernews", "Show HN: a thing", 0, 3))

    @Test
    fun `a middle item says Next`() =
        assertEquals("Next, from RSS: Weekly notes.", intro("rss", "Weekly notes", 1, 3))

    @Test
    fun `the last item says Finally`() =
        assertEquals("Finally, from RSS: Weekly notes.", intro("rss", "Weekly notes", 2, 3))

    @Test
    fun `a one-item briefing says From, not Finally`() =
        assertEquals("From RSS: Only one.", intro("rss", "Only one", 0, 1))

    @Test
    fun `an unlabelled source falls back to its id`() =
        assertEquals("Next, from mystery: A post.", intro("mystery", "A post", 1, 3))

    @Test
    fun `a blank label falls back to the id too`() =
        assertEquals("From blank: A post.", briefingIntroFor(item("blank", "A post"), 0, 2, { " " }, en))

    @Test
    fun `the title is trimmed`() =
        assertEquals("From RSS: Spaced.", intro("rss", "  Spaced \n", 0, 2))

    @Test(expected = IllegalArgumentException::class)
    fun `an index outside the briefing is a programming error`() {
        intro("rss", "x", 3, 3)
    }
}
