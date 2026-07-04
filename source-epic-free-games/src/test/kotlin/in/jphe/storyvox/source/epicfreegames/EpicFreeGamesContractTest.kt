package `in`.jphe.storyvox.source.epicfreegames

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.epicfreegames.net.EpicFreeGamesApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * Epic Free Games against the shared [FictionSourceContractTest]. The single
 * `freeGamesPromotions` endpoint is pointed at the kit's MockWebServer via the
 * [EpicFreeGamesApi.baseUrl] seam; `popular()` -> `fetchPromotions()` hits it,
 * so the IO-pin / auth / rate-limit / Cloudflare checks all exercise the real
 * request path.
 */
class EpicFreeGamesContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return EpicFreeGamesSource(
            object : EpicFreeGamesApi(client) {
                override val baseUrl: String get() = host
            },
        )
    }

    /** A trimmed real `freeGamesPromotions` body — one currently-free game and
     *  one upcoming freebie (see [EPIC_FIXTURE_BODY]). */
    override fun happyListBody(): String = EPIC_FIXTURE_BODY

    /** `/freeGamesPromotions?...` — the one endpoint this source reads. NOTE:
     *  the scaffold defaults this to the source id; Epic's path does not contain
     *  the id, so it must be the real path fragment. */
    override fun listPathFragment(): String = "freeGamesPromotions"
}
