package `in`.jphe.storyvox.source.questionablequesting

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.questionablequesting.net.QuestionablequestingApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class QuestionablequestingContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return QuestionablequestingSource(
            object : QuestionablequestingApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = """<html><body>
<div class="structItem structItem--thread" data-author="Quester">
 <div class="structItem-cell structItem-cell--main">
  <div class="structItem-title"><a href="/threads/a-chaotic-whirlpool.41108/" data-tp-primary="on">A Chaotic Whirlpool</a></div>
 </div>
 <div class="structItem-cell structItem-cell--meta"><dl><dt>Replies</dt><dd>1.2k</dd></dl></div>
</div>
</body></html>"""

    override fun listPathFragment(): String = "/forums/creative-writing.19/"
}
