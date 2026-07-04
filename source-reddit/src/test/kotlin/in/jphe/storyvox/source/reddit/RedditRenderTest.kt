package `in`.jphe.storyvox.source.reddit

import `in`.jphe.storyvox.source.reddit.net.RedditListingEnvelope
import `in`.jphe.storyvox.source.reddit.net.RedditPostBundle
import `in`.jphe.storyvox.source.reddit.net.RedditThingData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1492 — pure id-helper, JSON-parse, and body-rendering coverage
 * (no server, no coroutines).
 */
class RedditRenderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ─── id helpers ────────────────────────────────────────────────────

    @Test fun `fiction id round-trips through toSubreddit`() {
        assertEquals("reddit:books", redditFictionId("books"))
        assertEquals("books", "reddit:books".toSubreddit())
        assertEquals("books", "reddit:books::abc123".toSubreddit())
        assertNull("https://example.com".toSubreddit())
        assertNull("reddit:".toSubreddit())
    }

    @Test fun `chapter id composes and decodes`() {
        val cid = chapterIdFor("reddit:books", "abc123")
        assertEquals("reddit:books::abc123", cid)
        assertEquals("abc123", cid.substringAfterLast("::"))
    }

    @Test fun `normaliseSubreddit strips prefixes and slashes`() {
        assertEquals("Books", "  r/Books/ ".normaliseSubreddit())
        assertEquals("x", "/r/x".normaliseSubreddit())
        assertEquals("nosleep", "nosleep".normaliseSubreddit())
    }

    // ─── JSON parse tolerance ──────────────────────────────────────────

    @Test fun `subreddit listing parses and tolerates missing keys`() {
        val env = json.decodeFromString<RedditListingEnvelope>(SUBREDDIT_LISTING_JSON)
        assertEquals(2, env.data.children.size)
        assertEquals("books", env.data.children[0].data.displayName)
        // nosleep entry omits `title` — must degrade to null, not throw.
        assertNull(env.data.children[1].data.title)
        assertEquals("t5_2qh61", env.data.after)
    }

    // ─── body rendering ────────────────────────────────────────────────

    private fun post(selftext: String? = null, url: String? = null) = RedditThingData(
        id = "abc123",
        title = "A post",
        author = "op",
        selftext = selftext,
        url = url,
    )

    private fun comment(author: String?, body: String?) = RedditThingData(author = author, body = body)

    @Test fun `self post renders paragraphs and appended comments`() {
        val bundle = RedditPostBundle(
            post = post(selftext = "Para one.\n\nPara two."),
            comments = listOf(
                comment("alice", "Nice."),
                comment("bob", "Agreed."),
                comment(null, ""), // blank body dropped
            ),
        )
        val plain = bundle.toPlainText()
        assertTrue(plain.contains("Para one."))
        assertTrue(plain.contains("Para two."))
        assertTrue(plain.contains("Top comments:"))
        assertTrue(plain.contains("u/alice: Nice."))
        assertFalse(plain.contains("u/unknown:")) // blank comment not rendered

        val html = bundle.toHtml()
        assertTrue(html.contains("<p>Para one.</p>"))
        assertTrue(html.contains("<strong>u/alice:</strong>"))
    }

    @Test fun `link post degrades to a link note`() {
        val bundle = RedditPostBundle(
            post = post(selftext = "", url = "https://example.com/x"),
            comments = emptyList(),
        )
        val plain = bundle.toPlainText()
        assertTrue(plain.contains("link post"))
        assertTrue(plain.contains("https://example.com/x"))
    }

    @Test fun `html escaping neutralises angle brackets`() {
        val bundle = RedditPostBundle(
            post = post(selftext = "<script>alert(1)</script>"),
            comments = emptyList(),
        )
        val html = bundle.toHtml()
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
