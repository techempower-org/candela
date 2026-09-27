package `in`.jphe.storyvox.source.localhelp

import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.filter.FilterValue
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.localhelp.net.LocalHelpApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Behaviour beyond the contract kit: keyless posture, filter → query
 *  translation, and the narrated script. */
class LocalHelpSourceTest {

    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()
    private var detailCode = 200
    private var detailBody = DETAIL_BODY

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                synchronized(paths) { paths += p }
                return when {
                    p.contains("/api/Search/Keyword") ->
                        MockResponse().setBody(LocalHelpContractTest.SAMPLE_SEARCH_BODY)
                    p.contains("/api/ServiceAtLocation/v2") ->
                        MockResponse().setResponseCode(detailCode).setBody(detailBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown() }

    private fun source(key: String = "k"): LocalHelpSource {
        val host = server.url("/").toString().trimEnd('/')
        return LocalHelpSource(
            object : LocalHelpApi(OkHttpClient()) {
                override val baseUrl: String get() = host
                override fun apiKey(): String = key
            },
        )
    }

    @Test fun `keyless build makes no request and says not configured`() {
        val r = runBlocking { source(key = "").popular(1) }
        assertTrue("expected NetworkError, got $r", r is FictionResult.NetworkError)
        assertEquals(LocalHelpApi.NOT_CONFIGURED, (r as FictionResult.NetworkError).message)
        assertEquals(0, server.requestCount)
    }

    @Test fun `api key travels in the Api-Key header`() {
        runBlocking { source(key = "secret-123").popular(1) }
        assertEquals("secret-123", server.takeRequest().getHeader("Api-Key"))
    }

    @Test fun `popular omits Location so the API geocodes the device`() {
        runBlocking { source().popular(1) }
        val p = paths.single()
        assertTrue(p, p.contains("Keyword=food"))
        assertFalse(p, p.contains("Location="))
        assertTrue(p, p.contains("Skip=0") && p.contains("Top=10"))
    }

    @Test fun `filters become location, category keyword, distance and sort`() {
        val src = source()
        val state = FilterState()
            .with(LocalHelpSource.KEY_LOCATION, FilterValue.StringVal(" 95945 "))
            .with(LocalHelpSource.KEY_CATEGORY, FilterValue.StringVal("Housing & shelter"))
            .with(LocalHelpSource.KEY_DISTANCE, FilterValue.StringVal("25 miles"))
            .with("sort", FilterValue.StringVal("distance"))
        val q = src.applyFilters(SearchQuery(term = "", page = 2), state)
        runBlocking { src.search(q) }
        val p = paths.single()
        assertTrue(p, p.contains("Location=95945"))
        assertTrue(p, p.contains("Keyword=shelter%20housing"))
        assertTrue(p, p.contains("Distance=25"))
        assertTrue(p, p.contains("OrderBy=distance"))
        assertTrue(p, p.contains("Skip=10"))
    }

    @Test fun `blank search with no filters issues no request`() {
        val r = runBlocking { source().search(SearchQuery(term = "")) }
        assertTrue(r is FictionResult.Success)
        assertEquals(0, server.requestCount)
    }

    @Test fun `list maps ids, titles and paging`() {
        val r = runBlocking { source().popular(1) } as FictionResult.Success
        val items = r.value.items
        assertEquals(listOf("localhelp:sal-111", "localhelp:sal-222"), items.map { it.id })
        assertEquals("Emergency Food Pantry", items[0].title)
        assertEquals("Interfaith Food Ministry", items[0].author)
        assertEquals("Free groceries once a week. Bring an ID if you have one.", items[0].description)
        assertFalse(r.value.hasNext)
    }

    @Test fun `chapter narrates detail facts and always ends with 211`() {
        val src = source()
        runBlocking { src.popular(1) }
        val c = runBlocking {
            src.chapter("localhelp:sal-111", LocalHelpSource.chapterIdFor("localhelp:sal-111"))
        } as FictionResult.Success
        val body = c.value.plainBody
        assertTrue(body, body.startsWith("Emergency Food Pantry, offered by Interfaith Food Ministry."))
        assertTrue(body, body.contains("Phone: (530) 273-8132."))
        assertTrue(body, body.contains("Hours: Tuesday and Thursday, 9 AM to noon."))
        assertTrue(body, body.contains("Who can get help: Anyone in Nevada County."))
        assertTrue(body, body.contains("Where: 440 Henderson St, Grass Valley, CA 95945."))
        assertTrue(body, body.contains("call or text 2-1-1"))
        assertTrue(body, body.contains("provided by 211 Connecting Point"))
    }

    @Test fun `chapter falls back to the search hit when detail fails`() {
        detailCode = 500
        val src = source()
        runBlocking { src.popular(1) }
        val c = runBlocking { src.chapter("localhelp:sal-111", "localhelp:sal-111::info") }
        assertTrue("expected fallback narration, got $c", c is FictionResult.Success)
        val body = (c as FictionResult.Success).value.plainBody
        assertTrue(body, body.contains("Where: 440 Henderson St, Grass Valley, CA."))
    }

    @Test fun `uncached detail failure passes through typed`() {
        detailCode = 404
        val r = runBlocking { source().fictionDetail("localhelp:unknown") }
        assertTrue("expected NotFound, got $r", r is FictionResult.NotFound)
    }

    @Test fun `narration tolerates an empty detail object`() {
        val f = Two11Narration.factsFrom(Json.parseToJsonElement("{}"), Two11Narration.Facts())
        val script = Two11Narration.script(f)
        assertEquals("This service.", script.first())
        assertTrue(script.last().contains("provided by a local 2-1-1"))
    }

    companion object {
        /** Plausible v2 detail shape — the parser reads by key name, so the
         *  exact nesting is not load-bearing. */
        val DETAIL_BODY = """
        {
          "idServiceAtLocation": "sal-111",
          "dataOwner": "211 Connecting Point",
          "service": {
            "name": "Emergency Food Pantry",
            "description": "Free groceries once a week.",
            "eligibility": "Anyone in Nevada County",
            "fees": "Free",
            "applicationProcess": "Walk in",
            "phones": [{ "number": "(530) 273-8132", "type": "voice" }],
            "schedules": [{ "description": "Tuesday and Thursday, 9 AM to noon" }]
          },
          "organization": { "name": "Interfaith Food Ministry", "url": "https://example.org" },
          "location": {
            "physicalAddresses": [
              { "address1": "440 Henderson St", "city": "Grass Valley", "stateProvince": "CA", "postalCode": "95945" }
            ]
          }
        }
        """.trimIndent()
    }
}
