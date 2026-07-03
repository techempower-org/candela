package `in`.jphe.storyvox.source.gutenberg

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.gutenberg.net.GutendexApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient
import java.io.File

/**
 * Project Gutenberg (Gutendex) against the shared [FictionSourceContractTest].
 * The Gutendex host is pointed at the kit's MockWebServer via the [GutendexApi]
 * base seam; `popular()` -> `GET /books?sort=popular`.
 */
class GutenbergContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return GutenbergSource(
            object : GutendexApi(client) {
                override val baseUrl: String = host
            },
            File(System.getProperty("java.io.tmpdir"), "gutenberg-contract-test"),
        )
    }

    /** `/books` — a Gutendex list page envelope with no results. */
    override fun happyListBody(): String = """{"count":0,"results":[]}"""

    override fun listPathFragment(): String = "books"
}
