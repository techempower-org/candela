package `in`.jphe.storyvox.source.reddit

import `in`.jphe.storyvox.source.reddit.config.RedditConfig
import `in`.jphe.storyvox.source.reddit.config.RedditConfigState
import `in`.jphe.storyvox.source.reddit.config.RedditPostSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient

/** A static [RedditConfig] pointed at a MockWebServer for both hosts. */
internal fun fakeRedditConfig(
    host: String,
    clientId: String = "test-client-id",
    postSort: RedditPostSort = RedditPostSort.HOT,
    appendTopComments: Boolean = false,
    topCommentCount: Int = 5,
    favorites: List<String> = emptyList(),
): RedditConfig {
    val trimmed = host.trimEnd('/')
    val snapshot = RedditConfigState(
        clientId = clientId,
        deviceId = "test-device",
        postSort = postSort,
        appendTopComments = appendTopComments,
        topCommentCount = topCommentCount,
        favoriteSubreddits = favorites,
        tokenBaseUrl = trimmed,
        apiBaseUrl = trimmed,
    )
    return object : RedditConfig {
        override val state: Flow<RedditConfigState> = flowOf(snapshot)
        override suspend fun current(): RedditConfigState = snapshot
    }
}

/** A plain OkHttp client (no descriptive-UA interceptor needed for tests). */
internal fun testClient(): OkHttpClient = OkHttpClient.Builder().build()

/** Canned OAuth token-mint body. */
internal const val TOKEN_JSON: String =
    """{"access_token":"test-token","token_type":"bearer","expires_in":3600,"scope":"*"}"""

/** A subreddits-listing (popular / new / search) response, two subreddits. */
internal val SUBREDDIT_LISTING_JSON: String = """
    {
      "kind": "Listing",
      "data": {
        "after": "t5_2qh61",
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
            "public_description": "Realistic horror.",
            "subscribers": 17000000
          }}
        ]
      }
    }
""".trimIndent()

/** A `/r/<sub>/about` single-thing envelope. */
internal val ABOUT_JSON: String = """
    {
      "kind": "t5",
      "data": {
        "display_name": "books",
        "display_name_prefixed": "r/books",
        "title": "Books",
        "public_description": "The goodreads of reddit.",
        "subscribers": 24000000
      }
    }
""".trimIndent()

/** A subreddit posts Listing with two t3 posts. */
internal val POSTS_JSON: String = """
    {
      "kind": "Listing",
      "data": {
        "after": null,
        "children": [
          {"kind": "t3", "data": {
            "id": "abc123",
            "name": "t3_abc123",
            "title": "A great book thread",
            "author": "reader1",
            "selftext": "First paragraph.\n\nSecond paragraph.",
            "subreddit": "books",
            "permalink": "/r/books/comments/abc123/a_great_book_thread/",
            "created_utc": 1700000000.0,
            "num_comments": 42,
            "is_self": true
          }},
          {"kind": "t3", "data": {
            "id": "def456",
            "name": "t3_def456",
            "title": "Check out this article",
            "author": "reader2",
            "selftext": "",
            "url": "https://example.com/article",
            "subreddit": "books",
            "created_utc": 1700000500.0,
            "is_self": false
          }}
        ]
      }
    }
""".trimIndent()

/** A `/r/<sub>/comments/<id>` two-element array: [post, comments]. */
internal val POST_WITH_COMMENTS_JSON: String = """
    [
      {"kind": "Listing", "data": {"children": [
        {"kind": "t3", "data": {
          "id": "abc123",
          "title": "A great book thread",
          "author": "reader1",
          "selftext": "The full body of the post.\n\nWith two paragraphs.",
          "created_utc": 1700000000.0
        }}
      ]}},
      {"kind": "Listing", "data": {"children": [
        {"kind": "t1", "data": {"id": "c1", "author": "commenter1", "body": "Great point!", "score": 120}},
        {"kind": "t1", "data": {"id": "c2", "author": "commenter2", "body": "I disagree.", "score": 40}},
        {"kind": "t1", "data": {"id": "c3", "author": null, "body": ""}}
      ]}}
    ]
""".trimIndent()
