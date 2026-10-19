package `in`.jphe.storyvox.source.quotev

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.quotev.net.QuotevApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotevContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return QuotevSource(
            object : QuotevApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = QuotevFixtures.LIST

    override fun listPathFragment(): String = "/stories"

    @Test fun `quotev's own JS wall maps to the challenge result, not content`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(QuotevFixtures.CHALLENGE)
        }
        val result = runBlocking { createSourceForWall().popular(1) }
        assertTrue("expected Cloudflare, got $result", result is FictionResult.Cloudflare)
    }

    private fun createSourceForWall(): FictionSource =
        createSource(OkHttpClient(), server.url("/").toString())
}
