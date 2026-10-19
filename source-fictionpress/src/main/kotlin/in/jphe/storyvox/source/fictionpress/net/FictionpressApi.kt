package `in`.jphe.storyvox.source.fictionpress.net

import `in`.jphe.storyvox.source.fanfictionnet.engine.FfnEngineApi
import okhttp3.OkHttpClient
import javax.inject.Inject

/** FictionPress HTTP client — the shared FanFiction.net-engine client. */
internal open class FictionpressApi @Inject constructor(
    client: OkHttpClient,
) : FfnEngineApi(client, "FictionPress", BASE_URL) {
    companion object {
        const val BASE_URL = "https://www.fictionpress.com"
    }
}
