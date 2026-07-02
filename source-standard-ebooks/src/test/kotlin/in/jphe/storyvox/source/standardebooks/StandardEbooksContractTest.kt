package `in`.jphe.storyvox.source.standardebooks

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.standardebooks.net.StandardEbooksApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient
import java.io.File

/**
 * Standard Ebooks against the shared [FictionSourceContractTest], reusing the
 * existing [StandardEbooksApi.baseUrl] seam (the same one StandardEbooksApiTest
 * overrides). `popular()` -> `GET /ebooks?sort=popularity`. Standard Ebooks is
 * the second Cloudflare-aware source in the retrofit set (alongside AO3).
 */
class StandardEbooksContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return StandardEbooksSource(
            object : StandardEbooksApi(client) {
                override val baseUrl: String = host
            },
            File(System.getProperty("java.io.tmpdir"), "standard-ebooks-contract-test"),
        )
    }

    /** `/ebooks` listing — a well-formed but empty HTML page (Jsoup-tolerant). */
    override fun happyListBody(): String = "<html><body><ol class=\"ebooks-list\"></ol></body></html>"

    override fun listPathFragment(): String = "ebooks"
}
