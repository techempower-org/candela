package `in`.jphe.storyvox.source.sufficientvelocity.net

import `in`.jphe.storyvox.source.spacebattles.engine.XfEngineApi
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Sufficient Velocity HTTP client — the shared XenForo engine client pointed at SV. */
internal open class SufficientvelocityApi @Inject constructor(
    client: OkHttpClient,
) : XfEngineApi(client, "Sufficient Velocity", BASE_URL) {
    companion object {
        const val BASE_URL = "https://forums.sufficientvelocity.com"
    }
}
