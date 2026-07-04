package `in`.jphe.storyvox.source.epicfreegames.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * HTTP client for the Epic Games Store "free games promotions" feed (#1493).
 *
 * There is a single upstream endpoint —
 * `store-site-backend-static.ak.epicgames.com/freeGamesPromotions` — an
 * unofficial-but-long-stable, no-auth JSON document that the Epic storefront's
 * own "Free Games" widget consumes. We treat it as **fragile vendor JSON**:
 * every wire field is optional with a default, unknown keys are ignored, and a
 * malformed body surfaces as a typed [FictionResult.NetworkError] rather than a
 * thrown exception (see [request]).
 *
 * The [request] wrapper is the scaffold's pre-written shape and satisfies the
 * FictionSourceContractTest: it pins the blocking OkHttp call to
 * Dispatchers.IO (#585) and maps status codes to typed failures.
 */
internal open class EpicFreeGamesApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so unit tests point this at a MockWebServer without
     *  restructuring call sites (mirrors StandardEbooksApi.baseUrl). */
    internal open val baseUrl: String get() = BASE_URL

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * `GET /freeGamesPromotions` — the whole promotions document in one shot.
     * The single Browse/detail/chapter surface all read from this; there is no
     * per-game detail endpoint to fan out to.
     */
    suspend fun fetchPromotions(): FictionResult<PromotionsResponse> =
        request(PROMOTIONS_PATH) { parsePromotions(it) }

    /**
     * Parse the promotions document. Hoisted out of the network call so the
     * mapping unit test can exercise it against a captured fixture without an
     * OkHttp client. A shape mismatch throws [kotlinx.serialization.SerializationException],
     * which [request] catches and converts to a typed failure.
     */
    internal fun parsePromotions(text: String): PromotionsResponse =
        json.decodeFromString(text)

    /**
     * IO-pinned GET. `parse` turns the response body into your typed model.
     * Returns a typed [FictionResult] failure for every non-2xx status — never
     * throws for an HTTP error.
     */
    suspend fun <T> request(path: String, parse: (String) -> T): FictionResult<T> =
        withContext(Dispatchers.IO) {
            val url = baseUrl + path
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> FictionResult.NotFound("Epic Free Games: $path not found")
                        resp.code == 401 -> FictionResult.AuthRequired("HTTP 401 from $url")
                        resp.code == 403 -> {
                            // Cloudflare interstitials arrive as HTTP 403 with challenge
                            // HTML — the CF sniff MUST precede the auth mapping, or a
                            // CF-gated 403 misreports as "sign in required" (see
                            // docs/CONTRIBUTING-SOURCES.md decision table).
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "Epic Free Games returned a Cloudflare challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired("HTTP 403 from $url")
                            }
                        }
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = null,
                            message = "Epic Free Games rate limited (HTTP 429)",
                        )
                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )
                        else -> {
                            val text = resp.body?.string()
                                ?: return@withContext FictionResult.NetworkError(
                                    "empty body",
                                    IOException("empty body"),
                                )
                            FictionResult.Success(parse(text))
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            } catch (e: kotlinx.serialization.SerializationException) {
                // A throwing parse lambda must stay inside the typed-error
                // contract — never escape as a raw exception.
                FictionResult.NetworkError("Epic Free Games returned an unexpected response shape", e)
            }
        }

    /**
     * #1443-family heuristic: does a body look like a Cloudflare challenge
     * interstitial rather than your API's payload? Keep this arm ahead of
     * the 401/403 auth mapping (see the request() template above).
     */
    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        const val BASE_URL = "https://store-site-backend-static.ak.epicgames.com"

        /**
         * The free-games feed. `country`/`allowCountries` scope the promotions
         * to a storefront (giveaways are region-uniform in practice, but the
         * price strings and availability windows are localized). US/en-US is a
         * stable default; a future settings knob could localize this.
         */
        const val PROMOTIONS_PATH =
            "/freeGamesPromotions?locale=en-US&country=US&allowCountries=US"
    }
}

// ── Wire types ────────────────────────────────────────────────────────────
//
// Every field is nullable-with-default: the feed is unofficial and Epic
// reshapes it without notice. Missing/renamed fields degrade to null and are
// filtered downstream, never crash the parse.

@Serializable
internal data class PromotionsResponse(
    val data: CatalogData = CatalogData(),
)

@Serializable
internal data class CatalogData(
    @SerialName("Catalog") val catalog: Catalog = Catalog(),
)

@Serializable
internal data class Catalog(
    val searchStore: SearchStore = SearchStore(),
)

@Serializable
internal data class SearchStore(
    val elements: List<StoreElement> = emptyList(),
)

/** One catalog entry — a game or add-on that is currently or soon free, OR
 *  merely on sale (the feed bundles plain discounts too; the source filters
 *  those out — see [in.jphe.storyvox.source.epicfreegames.giveaways]). */
@Serializable
internal data class StoreElement(
    val title: String? = null,
    val id: String? = null,
    val namespace: String? = null,
    val description: String? = null,
    val seller: Seller? = null,
    val productSlug: String? = null,
    val urlSlug: String? = null,
    val keyImages: List<KeyImage> = emptyList(),
    val price: Price? = null,
    val promotions: Promotions? = null,
    val offerMappings: List<PageMapping> = emptyList(),
    val catalogNs: CatalogNs? = null,
)

@Serializable
internal data class Seller(val name: String? = null)

@Serializable
internal data class KeyImage(val type: String? = null, val url: String? = null)

@Serializable
internal data class Price(val totalPrice: TotalPrice? = null)

@Serializable
internal data class TotalPrice(
    val discountPrice: Long? = null,
    val originalPrice: Long? = null,
    val currencyCode: String? = null,
    val fmtPrice: FmtPrice? = null,
)

@Serializable
internal data class FmtPrice(
    val originalPrice: String? = null,
    val discountPrice: String? = null,
)

@Serializable
internal data class Promotions(
    val promotionalOffers: List<OfferGroup> = emptyList(),
    val upcomingPromotionalOffers: List<OfferGroup> = emptyList(),
)

@Serializable
internal data class OfferGroup(
    val promotionalOffers: List<PromotionalOffer> = emptyList(),
)

@Serializable
internal data class PromotionalOffer(
    val startDate: String? = null,
    val endDate: String? = null,
    val discountSetting: DiscountSetting? = null,
)

/**
 * Epic's `discountPercentage` is the percentage of the price the buyer still
 * PAYS, not the percentage off: `0` means "pay 0%" → the game is free, `50`
 * means half price. A giveaway is therefore an offer with
 * `discountPercentage == 0` — that single test separates the free games from
 * the plain sales the same feed carries.
 */
@Serializable
internal data class DiscountSetting(val discountPercentage: Int? = null)

@Serializable
internal data class PageMapping(val pageSlug: String? = null, val pageType: String? = null)

@Serializable
internal data class CatalogNs(val mappings: List<PageMapping> = emptyList())
