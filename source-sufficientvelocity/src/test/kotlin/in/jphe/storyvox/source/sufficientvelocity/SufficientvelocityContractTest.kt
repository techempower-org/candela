package `in`.jphe.storyvox.source.sufficientvelocity

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.sufficientvelocity.net.SufficientvelocityApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class SufficientvelocityContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return SufficientvelocitySource(
            object : SufficientvelocityApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = """<html><body>
<div class="structItem structItem--thread" data-author="Writer">
 <div class="structItem-cell structItem-cell--main">
  <div class="structItem-title"><a href="/threads/a-practical-guide-to-sorcery.123/" data-tp-primary="on">A Practical Guide to Sorcery</a></div>
 </div>
 <div class="structItem-cell structItem-cell--meta"><dl><dt>Replies</dt><dd>4.2k</dd></dl></div>
</div>
</body></html>"""

    override fun listPathFragment(): String = "/forums/user-fiction.2/"
}
