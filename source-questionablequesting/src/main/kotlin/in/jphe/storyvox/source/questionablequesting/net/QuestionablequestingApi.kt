package `in`.jphe.storyvox.source.questionablequesting.net

import `in`.jphe.storyvox.source.spacebattles.engine.XfEngineApi
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Questionable Questing HTTP client — the shared XenForo engine client pointed at QQ. */
internal open class QuestionablequestingApi @Inject constructor(
    client: OkHttpClient,
) : XfEngineApi(client, "Questionable Questing", BASE_URL) {
    companion object {
        const val BASE_URL = "https://forum.questionablequesting.com"
    }
}
