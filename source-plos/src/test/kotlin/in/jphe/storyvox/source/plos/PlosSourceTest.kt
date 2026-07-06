package `in`.jphe.storyvox.source.plos

import `in`.jphe.storyvox.source.plos.net.PlosArticleHit
import `in`.jphe.storyvox.source.plos.net.PlosResponseBlock
import `in`.jphe.storyvox.source.plos.net.PlosSearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the PLOS source (#380).
 *
 * Covers the three required surfaces:
 *
 *  1. Search JSON parses into [PlosSearchResponse] cleanly, even with
 *     extra Solr fields we don't model.
 *  2. Article HTML body extraction picks the right slices from a
 *     representative PLOS article page fixture (abstract + first
 *     three body sections; references etc. excluded).
 *  3. DOI → article-URL composition handles the seven-journal slug
 *     mapping correctly + falls back sensibly on unknown slugs.
 */
class PlosSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 1. Search JSON parse ─────────────────────────────────────────

    @Test
    fun `parses representative PLOS search JSON`() {
        // Real-shape Solr response — `responseHeader` and friends that
        // we don't model are present so the parser has to honor
        // ignoreUnknownKeys. `abstract` is the single-element array
        // shape PLOS actually emits.
        val raw = """
            {
              "responseHeader": { "status": 0, "QTime": 12 },
              "response": {
                "numFound": 1247,
                "start": 0,
                "docs": [
                  {
                    "id": "10.1371/journal.pone.0123456",
                    "title": "On the migration of Arctic foxes",
                    "abstract": ["Foxes migrate. We measured how far."],
                    "author_display": ["Smith J", "Jones K", "Lee A"],
                    "journal": "PLOS ONE",
                    "publication_date": "2026-04-01T00:00:00Z"
                  },
                  {
                    "id": "10.1371/journal.pbio.0099999",
                    "title": "Crows count: evidence for numerical cognition",
                    "abstract": ["Some crows can count."],
                    "author_display": ["Park C"],
                    "journal": "PLOS Biology",
                    "publication_date": "2026-04-02T00:00:00Z"
                  }
                ]
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString<PlosSearchResponse>(raw)
        assertEquals(1247, parsed.response.numFound)
        assertEquals(2, parsed.response.docs.size)
        val first = parsed.response.docs[0]
        assertEquals("10.1371/journal.pone.0123456", first.id)
        assertEquals("On the migration of Arctic foxes", first.title)
        assertEquals(1, first.abstract?.size)
        assertEquals(3, first.authorDisplay?.size)
        assertEquals("PLOS ONE", first.journal)
    }

    @Test
    fun `parses search response with missing optional fields`() {
        // PLOS occasionally returns docs with no abstract (preprints,
        // editorials) or no author_display (anonymous corrections).
        // Both should deserialize as nulls rather than failing the
        // whole batch.
        val raw = """
            {
              "response": {
                "numFound": 1,
                "start": 0,
                "docs": [
                  { "id": "10.1371/journal.pone.0000001", "title": "Minimal doc" }
                ]
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString<PlosSearchResponse>(raw)
        assertEquals(1, parsed.response.docs.size)
        assertEquals("10.1371/journal.pone.0000001", parsed.response.docs[0].id)
        assertNull(parsed.response.docs[0].abstract)
        assertNull(parsed.response.docs[0].authorDisplay)
    }

    // ── 2. Article HTML body extraction ──────────────────────────────

    @Test
    fun `extractArticleBody pulls abstract and first three sections`() {
        // Minimal stand-in for a PLOS article page. The real article
        // pages carry hundreds of nav/style/script tags around the
        // landmark elements; the extractor only keys on the abstract
        // div + section divs so this fixture exercises the same
        // extraction path as a real fetch.
        val html = """
            <html><body>
              <header>nav bar etc</header>
              <article>
                <div class="abstract abstract-type-abstract">
                  <p>This is the abstract paragraph.</p>
                </div>
                <div class="section toc-section" id="section1">
                  <h2>Introduction</h2>
                  <p>Introduction prose.</p>
                </div>
                <div class="section toc-section" id="section2">
                  <h2>Methods</h2>
                  <p>Methods prose.</p>
                </div>
                <div class="section toc-section" id="section3">
                  <h2>Results</h2>
                  <p>Results prose.</p>
                </div>
                <div class="section toc-section" id="section4">
                  <h2>Discussion</h2>
                  <p>Discussion prose — should NOT appear in v1.</p>
                </div>
                <div class="section toc-section" id="section5">
                  <h2>References</h2>
                  <p>[1] cite cite cite</p>
                </div>
              </article>
            </body></html>
        """.trimIndent()

        val body = extractArticleBody(html, abstract = null, maxSections = 3)
        // Abstract present, first three sections present, fourth/fifth
        // sections explicitly excluded.
        assertTrue(body.html.contains("This is the abstract paragraph"))
        assertTrue(body.plain.contains("Introduction prose"))
        assertTrue(body.plain.contains("Methods prose"))
        assertTrue(body.plain.contains("Results prose"))
        assertFalse(body.plain.contains("Discussion prose"))
        assertFalse(body.plain.contains("cite cite cite"))
    }

    @Test
    fun `extractArticleBody prefers Solr abstract over HTML when supplied`() {
        // Belt-and-braces: when the API hands back a clean
        // Solr-side abstract (no HTML), we use it instead of the
        // in-page abstract div. Avoids accidental double-rendering
        // when the HTML abstract carries figure captions.
        val html = """
            <div class="abstract">
              <p>HTML abstract — should NOT be used.</p>
            </div>
            <div class="section" id="section1"><h2>Intro</h2><p>Body.</p></div>
        """.trimIndent()
        val body = extractArticleBody(
            html = html,
            abstract = "Clean Solr abstract.",
            maxSections = 1,
        )
        assertTrue(body.plain.contains("Clean Solr abstract"))
        assertFalse(body.plain.contains("HTML abstract"))
    }

    @Test
    fun `extractArticleBody falls back to whole-page strip when no landmarks`() {
        // Articles with unrecognized markup shouldn't render an empty
        // chapter — better to ship the whole-page text strip than a
        // blank screen.
        val html = "<html><body><p>No standard markup here, just prose.</p></body></html>"
        val body = extractArticleBody(html)
        assertTrue(body.plain.contains("No standard markup here"))
    }

    // ── 3. DOI → article URL composition ─────────────────────────────

    @Test
    fun `articleUrlForDoi maps each journal slug to the right directory`() {
        // Spot-check every slug in the table. The DOI shape is
        // `10.1371/journal.<slug>.<seq>` — slug determines the
        // journals.plos.org directory.
        assertEquals(
            "https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0123456",
            articleUrlForDoi("10.1371/journal.pone.0123456"),
        )
        assertEquals(
            "https://journals.plos.org/plosbiology/article?id=10.1371/journal.pbio.3000001",
            articleUrlForDoi("10.1371/journal.pbio.3000001"),
        )
        assertEquals(
            "https://journals.plos.org/plosmedicine/article?id=10.1371/journal.pmed.1000001",
            articleUrlForDoi("10.1371/journal.pmed.1000001"),
        )
        assertEquals(
            "https://journals.plos.org/ploscompbiol/article?id=10.1371/journal.pcbi.1000001",
            articleUrlForDoi("10.1371/journal.pcbi.1000001"),
        )
        assertEquals(
            "https://journals.plos.org/plosgenetics/article?id=10.1371/journal.pgen.1000001",
            articleUrlForDoi("10.1371/journal.pgen.1000001"),
        )
        assertEquals(
            "https://journals.plos.org/plospathogens/article?id=10.1371/journal.ppat.1000001",
            articleUrlForDoi("10.1371/journal.ppat.1000001"),
        )
        assertEquals(
            "https://journals.plos.org/plosntds/article?id=10.1371/journal.pntd.0001234",
            articleUrlForDoi("10.1371/journal.pntd.0001234"),
        )
    }

    @Test
    fun `articleUrlForDoi falls back to PLOS ONE for unknown slug`() {
        // A future journal we don't know about should still produce a
        // URL — the worst case is a 404 surfaced as NotFound rather
        // than a hard crash composing the URL.
        val url = articleUrlForDoi("10.1371/journal.xxxx.0000001")
        assertTrue(url.startsWith("https://journals.plos.org/plosone/"))
        assertTrue(url.endsWith("?id=10.1371/journal.xxxx.0000001"))
    }

    @Test
    fun `articleUrlForDoi honors the Solr journal hint when slug is unknown`() {
        // When the DOI's slug is unfamiliar but the Solr `journal`
        // field comes through, we should pick the matching directory
        // from the human-readable journal name. Belt-and-braces for
        // future journals before our slug table catches up.
        val url = articleUrlForDoi(
            doi = "10.1371/journal.xxxx.0000001",
            journalHint = "PLOS Biology",
        )
        assertEquals(
            "https://journals.plos.org/plosbiology/article?id=10.1371/journal.xxxx.0000001",
            url,
        )
    }

    // ── id round-trip + summary mapping ──────────────────────────────

    @Test
    fun `fictionId roundtrip preserves DOI`() {
        val id = plosFictionId("10.1371/journal.pone.0123456")
        assertEquals("plos:10.1371/journal.pone.0123456", id)
        assertEquals("10.1371/journal.pone.0123456", id.toPlosDoi())
        // Chapter id (with `::body` suffix) still resolves to the same
        // DOI when run through the same parser — the chapter() entry
        // point passes the fiction-id through toPlosDoi() and trims
        // the suffix.
        val chapter = chapterIdFor(id)
        assertEquals("10.1371/journal.pone.0123456", chapter.toPlosDoi())
    }

    @Test
    fun `toPlosDoi rejects ids without the plos prefix`() {
        assertNull("wikipedia:Marie_Curie".toPlosDoi())
        assertNull("plos:".toPlosDoi())
        assertNotNull("plos:10.1371/journal.pone.0000001".toPlosDoi())
    }

    // #1628 — htmlToPlainText moved to the shared core-data util; its
    // transformation is covered by core-data HtmlPlainTextTest.
}
