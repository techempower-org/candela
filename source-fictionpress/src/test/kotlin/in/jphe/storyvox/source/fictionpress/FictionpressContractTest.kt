package `in`.jphe.storyvox.source.fictionpress

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.fictionpress.net.FictionpressApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class FictionpressContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return FictionpressSource(
            object : FictionpressApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = """<html><body>
<div class="z-list zhover zpointer "><a class="stitle" href="/s/3456789/1/Paper-Moons">Paper Moons</a>
by <a href="/u/99/Writer">Writer</a>
<div class="z-indent z-padtop">Original romance.<div class="z-padtop2 xgray">Rated: T - English - Romance - Chapters: 12 - Words: 40,000</div></div></div>
</body></html>"""

    override fun listPathFragment(): String = "/fiction/Romance/"
}
