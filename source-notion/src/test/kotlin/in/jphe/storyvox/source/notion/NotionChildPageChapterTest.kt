package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.source.notion.net.NotionBlock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1508 — child-page chaptering. A Notion page whose sub-pages are the
 * real chapters (JP's shorts DB: a lead of notes + N `child_page` blocks)
 * has no `heading_1`, so the old `splitOnHeading1` collapsed everything
 * into one "Intro" chapter. [planChapters] must instead emit a lead
 * Section (when there are non-child blocks) + one Child per sub-page.
 */
class NotionChildPageChapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Test titles/text below are quote-free, so direct interpolation into
    // the JSON string literal is safe (no escaping needed).
    private fun childPage(id: String, title: String): NotionBlock =
        NotionBlock(
            id = id,
            type = "child_page",
            childPage = json.parseToJsonElement("""{"title":"$title"}"""),
        )

    private fun para(text: String): NotionBlock =
        NotionBlock(
            id = "p",
            type = "paragraph",
            paragraph = json.parseToJsonElement(
                """{"rich_text":[{"type":"text","plain_text":"$text"}]}""",
            ),
        )

    private fun heading1(text: String): NotionBlock =
        NotionBlock(
            id = "h",
            type = "heading_1",
            heading1 = json.parseToJsonElement(
                """{"rich_text":[{"type":"text","plain_text":"$text"}]}""",
            ),
        )

    @Test fun `lead notes plus child pages become intro plus one chapter each`() {
        val blocks = listOf(
            para("Recording notes"),
            childPage("aaaa1111-2222-3333-4444-555566667777", "Short 01 — Myth-busting"),
            childPage("bbbb1111-2222-3333-4444-555566667777", "Short 02 — Getting connected"),
            childPage("cccc1111-2222-3333-4444-555566667777", "Short 03 — Food"),
        )
        val plans = planChapters(blocks)
        assertEquals("intro + 3 shorts", 4, plans.size)
        assertTrue("first is the lead section", plans[0] is NotionChapterPlan.Section)
        assertEquals("Introduction", plans[0].title)
        assertTrue("rest are child pages", plans.drop(1).all { it is NotionChapterPlan.Child })
        assertEquals("Short 02 — Getting connected", plans[2].title)
        assertEquals(
            "child id is compact",
            "bbbb1111222233334444555566667777",
            (plans[2] as NotionChapterPlan.Child).childPageId,
        )
    }

    @Test fun `page of only child pages gets no empty intro`() {
        val blocks = listOf(
            childPage("aaaa1111-2222-3333-4444-555566667777", "Short 01"),
            childPage("bbbb1111-2222-3333-4444-555566667777", "Short 02"),
        )
        val plans = planChapters(blocks)
        assertEquals(2, plans.size)
        assertTrue(plans.all { it is NotionChapterPlan.Child })
    }

    @Test fun `flat page with no child pages still splits on heading_1`() {
        val blocks = listOf(
            para("lead"),
            heading1("Chapter One"),
            para("body one"),
            heading1("Chapter Two"),
            para("body two"),
        )
        val plans = planChapters(blocks)
        assertTrue("all sections", plans.all { it is NotionChapterPlan.Section })
        assertEquals(listOf("Introduction", "Chapter One", "Chapter Two"), plans.map { it.title })
    }

    @Test fun `childChapterId round-trips the compact id with a child marker`() {
        val id = childChapterIdFor("notion:abc", "bbbb1111-2222-3333-4444-555566667777")
        assertEquals("notion:abc::child-bbbb1111222233334444555566667777", id)
        assertEquals(
            "bbbb1111222233334444555566667777",
            id.substringAfterLast("::child-", ""),
        )
    }

    @Test fun `leadBlocks drops child pages, keeps the rest`() {
        val blocks = listOf(
            para("keep me"),
            childPage("aaaa1111-2222-3333-4444-555566667777", "drop me"),
        )
        assertEquals(1, leadBlocks(blocks).size)
        assertEquals("paragraph", leadBlocks(blocks).first().type)
    }
}
