package `in`.jphe.storyvox.source.rss.net

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #1489 — `Retry-After` parsing for the 429 → RateLimited mapping
 * (reddit throttles the app's UA on `.rss`). Pure logic, plain JUnit — no
 * Android, no network (OkHttpClient() is a plain-JVM object).
 */
class RssFetcherRetryAfterTest {

    private val fetcher = RssFetcher(OkHttpClient())

    @Test
    fun `delta-seconds form parses to a Duration`() {
        assertEquals(120.seconds, fetcher.parseRetryAfter("120"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(30.seconds, fetcher.parseRetryAfter("  30 "))
    }

    @Test
    fun `null header yields null`() {
        assertNull(fetcher.parseRetryAfter(null))
    }

    @Test
    fun `HTTP-date form is not parsed (returns null, UI falls back to generic)`() {
        assertNull(fetcher.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `garbage yields null`() {
        assertNull(fetcher.parseRetryAfter("soon"))
    }

    @Test
    fun `negative value is rejected`() {
        assertNull(fetcher.parseRetryAfter("-5"))
    }
}
