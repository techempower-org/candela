package `in`.jphe.storyvox.source.fanfictionnet.net

import `in`.jphe.storyvox.source.fanfictionnet.engine.FfnEngineApi
import okhttp3.OkHttpClient
import javax.inject.Inject

/** FanFiction.net HTTP client — the shared engine client pointed at FFN. */
internal open class FanfictionnetApi @Inject constructor(
    client: OkHttpClient,
) : FfnEngineApi(client, "FanFiction.net", BASE_URL) {
    companion object {
        const val BASE_URL = "https://www.fanfiction.net"
    }
}
