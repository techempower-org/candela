package `in`.jphe.storyvox.source.localhelp

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.localhelp.net.LocalHelpApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * Local Help (211 NDP, keyed) against the shared contract kit. popular() is a
 * `Search/Keyword` call, so that is the list path the kit routes the happy
 * body to.
 */
class LocalHelpContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return LocalHelpSource(
            object : LocalHelpApi(client) {
                override val baseUrl: String get() = host
                // #1529 — a non-blank fake key so request() actually reaches
                // the wire (a blank key short-circuits to "not configured").
                override fun apiKey(): String = "contract-fake-key"
            },
        )
    }

    override fun happyListBody(): String = SAMPLE_SEARCH_BODY

    override fun listPathFragment(): String = "/api/Search/Keyword"

    companion object {
        /** Trimmed to the documented `DocumentSearchResult[Resource]` shape. */
        const val SAMPLE_SEARCH_BODY: String = """
        {
          "count": 2,
          "coverage": null,
          "facets": {},
          "results": [
            {
              "score": 9.1,
              "highlights": {},
              "document": {
                "id": "doc-1",
                "idServiceAtLocation": "sal-111",
                "idService": "svc-1",
                "idOrganization": "org-1",
                "idLocation": "loc-1",
                "nameService": "Emergency Food Pantry",
                "descriptionService": "<p>Free groceries once a week. Bring an ID if you have one.</p>",
                "nameOrganization": "Interfaith Food Ministry",
                "address1PhysicalAddress": "440 Henderson St",
                "cityPhysicalAddress": "Grass Valley",
                "statePhysicalAddress": "CA",
                "topicTaxonomy": ["Basic Needs"],
                "subtopicTaxonomy": ["Food"],
                "dataOwner": "211 Connecting Point"
              }
            },
            {
              "score": 7.4,
              "document": {
                "id": "doc-2",
                "idServiceAtLocation": "sal-222",
                "nameService": "Hot Meals",
                "nameOrganization": "Hospitality House"
              }
            }
          ]
        }
        """
    }
}
