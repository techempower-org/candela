package `in`.jphe.storyvox.source.reddit.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Issue #1492 — reddit JSON API wire types.
 *
 * Reddit wraps everything in a "thing" envelope: a `kind` discriminator
 * (`t1` comment, `t3` link/post, `t5` subreddit, `Listing`) plus a
 * `data` payload. Listings carry an `after` cursor + a `children` array
 * of things. We model one tolerant [RedditThingData] that unions the
 * fields we read across the three kinds we care about — `ignoreUnknownKeys`
 * discards the rest, so a schema addition upstream never breaks parsing.
 *
 * All fields are nullable/defaulted: reddit omits keys liberally
 * depending on the endpoint (e.g. `about` carries subscribers; a search
 * hit may not), and a missing key must degrade to "unknown", never a
 * `SerializationException`.
 */

/** A `Listing` envelope: `{ "kind": "Listing", "data": { after, children } }`. */
@Serializable
internal data class RedditListingEnvelope(
    val kind: String? = null,
    val data: RedditListingData = RedditListingData(),
)

@Serializable
internal data class RedditListingData(
    val after: String? = null,
    val children: List<RedditThing> = emptyList(),
)

/** One thing: `{ "kind": "t3", "data": { … } }`. */
@Serializable
internal data class RedditThing(
    val kind: String? = null,
    val data: RedditThingData = RedditThingData(),
)

/**
 * Union of the fields we read from t5 (subreddit), t3 (post/link), and
 * t1 (comment). Every field is optional; the source layer decides which
 * subset is meaningful for the kind it asked for.
 */
@Serializable
internal data class RedditThingData(
    // ── t5 subreddit ──────────────────────────────────────────────
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("display_name_prefixed") val displayNamePrefixed: String? = null,
    val title: String? = null,
    @SerialName("public_description") val publicDescription: String? = null,
    val subscribers: Long? = null,
    val over18: Boolean = false,
    val url: String? = null,

    // ── t3 post / link ────────────────────────────────────────────
    val id: String? = null,
    val name: String? = null,
    val author: String? = null,
    val selftext: String? = null,
    val subreddit: String? = null,
    val permalink: String? = null,
    @SerialName("created_utc") val createdUtc: Double? = null,
    @SerialName("num_comments") val numComments: Int? = null,
    val score: Int? = null,
    @SerialName("is_self") val isSelf: Boolean? = null,
    val stickied: Boolean = false,
    @SerialName("over_18") val overEighteen: Boolean = false,

    // ── t1 comment ────────────────────────────────────────────────
    val body: String? = null,
)

/**
 * `about` returns a single thing (not a Listing):
 * `{ "kind": "t5", "data": { … } }`.
 */
@Serializable
internal data class RedditThingEnvelope(
    val kind: String? = null,
    val data: RedditThingData = RedditThingData(),
)

/** OAuth token-mint response from `/api/v1/access_token`. */
@Serializable
internal data class RedditTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    val error: String? = null,
)
