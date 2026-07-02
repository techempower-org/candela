package `in`.jphe.storyvox.source.ao3

import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import java.io.File

/**
 * AO3 against the shared [FictionSourceContractTest] — the marquee retrofit: it
 * exercises the anonymous per-tag Atom feed (`popular()` -> `/tags/<id>/feed.atom`)
 * and proves the Cloudflare + auth mappings for real (AO3 already detects CF and,
 * with this epic's fix, maps 401/403 -> AuthRequired and 429 -> RateLimited).
 *
 * The [Ao3Api.baseUrl] seam points both the anonymous and authed clients at the
 * kit's MockWebServer. Search short-circuits to AuthRequired without a captured
 * session (no network), which is exactly what the blank-search contract checks.
 */
class Ao3ContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        val api = object : Ao3Api(client, client) {
            override val baseUrl: String get() = host
        }
        return Ao3Source(
            api,
            File(System.getProperty("java.io.tmpdir"), "ao3-contract-test"),
            FakeAuthDao(),
        )
    }

    /** A well-formed but empty AO3 Atom feed (XmlPullParser yields zero entries). */
    override fun happyListBody(): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<feed xmlns="http://www.w3.org/2005/Atom"><title>AO3</title></feed>"""

    override fun listPathFragment(): String = "feed.atom"

    /** No captured session — `get`/`observe` return null so the source stays anonymous. */
    private class FakeAuthDao : AuthDao {
        override fun observe(sourceId: String): Flow<AuthCookie?> = flowOf(null)
        override suspend fun get(sourceId: String): AuthCookie? = null
        override suspend fun upsert(cookie: AuthCookie) {}
        override suspend fun touchVerified(sourceId: String, now: Long) {}
        override suspend fun clear(sourceId: String) {}
    }
}
