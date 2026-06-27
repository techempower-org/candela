package `in`.jphe.storyvox.source.ao3.net

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1141 — process-wide politeness gate for archiveofourown.org.
 *
 * AO3 is run by the Organization for Transformative Works, which has
 * publicly opposed scraping + AI ingestion (their robots.txt blocks
 * GPTBot / CCBot / ChatGPT-User outright). Of storyvox's high-risk
 * sources, AO3 was the *only* one with no request throttling at all —
 * both OkHttp clients (anonymous + authed) were plain. Royal Road, a
 * lower-risk source, already enforces a 1 req/sec gate
 * ([`in`.jphe.storyvox.source.royalroad.net.RateLimitedClient]); AO3
 * being the unguarded one was the wrong posture.
 *
 * This interceptor enforces a [MIN_REQUEST_INTERVAL_MS] floor between
 * the *start* of consecutive requests, shared across both AO3 clients
 * (it's a [Singleton] injected into both builders) so the anonymous
 * catalog fetches and the authed subscription / Marked-for-Later reads
 * draw from one politeness budget rather than two independent ones.
 *
 * Why a blocking sleep rather than a coroutine `delay`: OkHttp
 * interceptors run synchronously on the client's dispatcher threads,
 * never on the caller's coroutine. Blocking one of those worker
 * threads briefly is exactly what they exist for, and it keeps the
 * gate transparent to every call site in [Ao3Api] — no `suspend`
 * plumbing, no change to the request-building code.
 *
 * Deliberately NOT a robots.txt enforcer: AO3's robots.txt disallows
 * `/downloads/` (the only EPUB content path) and `/works/search?` for
 * the wildcard user-agent, so strict enforcement would break the
 * source. That trade-off is a product decision (see issue #1141), not
 * something to encode silently here. This gate only spaces requests.
 */
@Singleton
internal class Ao3RateLimitInterceptor @Inject constructor() : Interceptor {

    private val lock = Any()

    @Volatile
    private var lastRequestAt: Long = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val wait = (lastRequestAt + MIN_REQUEST_INTERVAL_MS) - now
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    // Restore the flag so OkHttp's own cancellation /
                    // timeout handling downstream still observes it.
                    Thread.currentThread().interrupt()
                }
            }
            lastRequestAt = System.currentTimeMillis()
        }
        return chain.proceed(chain.request())
    }

    companion object {
        /** One request per second floor — matches Royal Road's
         *  [`in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds.MIN_REQUEST_INTERVAL_MS].
         *  AO3's access pattern (paginated Atom Browse + one EPUB per
         *  work open) is naturally low-volume, so this is cheap
         *  insurance against bursts (rapid successive opens, fast Browse
         *  paging) rather than a throughput bottleneck. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
    }
}
