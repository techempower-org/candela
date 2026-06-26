package `in`.jphe.storyvox.source.radio

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.radio.net.RadioBrowserApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #1134 — regression guard for the Radio Browser transport.
 *
 * `byName`/`search` route through `doRequest`, which runs a blocking
 * OkHttp `.execute()`. That call MUST happen on `Dispatchers.IO`. Before
 * the fix, `doRequest` was a plain (non-suspend) function with no
 * `withContext(Dispatchers.IO)`, so a `byName` call originating on
 * `Dispatchers.Main` (BrowseViewModel.viewModelScope) blocked — and
 * crashed — the main thread with `NetworkOnMainThreadException`. Both
 * `source-radio` and `source-librivox` shipped this bug because the
 * repository deliberately does not wrap browse/search; each source API
 * owns its own IO dispatch (the #585 convention).
 *
 * This is a plain JVM unit test (no Robolectric, per the project's test
 * convention), so `StrictMode.detectNetwork()` is inert. Instead we assert
 * the thread that actually runs the HTTP call differs from the thread that
 * called the API: `withContext(Dispatchers.IO)` guarantees that hop; the
 * bug ran the call inline on the caller thread. Fails red on the old code,
 * passes green on the fix.
 */
class RadioBrowserApiThreadingTest {

    @Test
    fun `byName runs the blocking HTTP call off the caller thread (issue 1134)`() = runBlocking {
        val callerThread = Thread.currentThread()
        val ranOn = AtomicReference<Thread?>(null)

        // Short-circuit interceptor: record the thread the OkHttp call runs
        // on and return a synthetic empty-array body — no real socket.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                ranOn.set(Thread.currentThread())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val result = RadioBrowserApi(client).byName("anything")

        // End-to-end still works: empty array → empty success.
        assertTrue("expected Success, got $result", result is FictionResult.Success)
        val executed = ranOn.get()
        assertNotNull("interceptor never ran — no request was made", executed)
        assertNotEquals(
            "Issue #1134: doRequest must run on Dispatchers.IO, not the caller thread",
            callerThread,
            executed,
        )
    }
}
