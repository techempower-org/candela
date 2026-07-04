package `in`.jphe.storyvox.source.reddit

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.reddit.config.RedditConfig
import `in`.jphe.storyvox.source.reddit.config.RedditConfigState
import `in`.jphe.storyvox.source.reddit.net.RedditApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * Reddit against the shared contract kit.
 *
 * The kit exercises `popular()`, which issues **two** requests: the
 * OAuth token mint (`POST /api/v1/access_token`) and the list fetch
 * (`GET /subreddits/popular`). [route] serves both so the happy-path
 * IO-pin check covers the whole chain. The kit's 401/429/CF cases swap
 * in a constant responder that hits the token mint first — proving the
 * OAuth handshake obeys the same typed-failure contract as the API.
 */
class RedditContractTest : FictionSourceContractTest() {

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        val fakeConfig = object : RedditConfig {
            private val snapshot = RedditConfigState(
                clientId = "test-client-id",
                deviceId = "test-device",
                // Point BOTH the token mint and the API at the MockWebServer.
                tokenBaseUrl = host,
                apiBaseUrl = host,
            )
            override val state: Flow<RedditConfigState> = flowOf(snapshot)
            override suspend fun current(): RedditConfigState = snapshot
        }
        return RedditSource(RedditApi(client, fakeConfig), fakeConfig)
    }

    /** Route the OAuth token mint + the subreddit list to canned bodies;
     *  everything else 404s (the kit's other cases override the dispatcher). */
    override fun route(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            path.contains("access_token") ->
                MockResponse().setResponseCode(200).setBody(TOKEN_BODY)
            path.contains(listPathFragment()) ->
                MockResponse().setResponseCode(200).setBody(happyListBody())
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** A trimmed real `/subreddits/popular` Listing (two subreddits). */
    override fun happyListBody(): String = """
        {
          "kind": "Listing",
          "data": {
            "after": null,
            "children": [
              {"kind": "t5", "data": {
                "display_name": "books",
                "display_name_prefixed": "r/books",
                "title": "Books",
                "public_description": "The goodreads of reddit.",
                "subscribers": 24000000
              }},
              {"kind": "t5", "data": {
                "display_name": "nosleep",
                "display_name_prefixed": "r/nosleep",
                "public_description": "Realistic horror stories.",
                "subscribers": 17000000
              }}
            ]
          }
        }
    """.trimIndent()

    /** popular() hits `/subreddits/popular`. */
    override fun listPathFragment(): String = "subreddits"

    private companion object {
        const val TOKEN_BODY: String =
            """{"access_token":"test-token","token_type":"bearer","expires_in":3600,"scope":"*"}"""
    }
}
