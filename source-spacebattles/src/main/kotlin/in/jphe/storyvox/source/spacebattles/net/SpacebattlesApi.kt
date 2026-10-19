package `in`.jphe.storyvox.source.spacebattles.net

import `in`.jphe.storyvox.source.spacebattles.engine.XfEngineApi
import okhttp3.OkHttpClient
import javax.inject.Inject

/** SpaceBattles HTTP client — the shared XenForo engine client pointed at SB. */
internal open class SpacebattlesApi @Inject constructor(
    client: OkHttpClient,
) : XfEngineApi(client, "SpaceBattles", BASE_URL) {
    companion object {
        const val BASE_URL = "https://forums.spacebattles.com"
    }
}
