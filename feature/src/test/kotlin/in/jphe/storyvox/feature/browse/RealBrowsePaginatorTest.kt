package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealBrowsePaginatorTest {

    // -- Fixtures ----------------------------------------------------------------

    private fun summary(id: String, title: String = id) = FictionSummary(
        id = id, sourceId = "rr", title = title, author = "auth-$id",
        status = FictionStatus.ONGOING,
    )

    private fun page(items: List<FictionSummary>, page: Int, hasNext: Boolean) =
        FictionResult.Success(ListPage(items, page, hasNext))

    /** Programmable fetch lambda. Each call returns the next stubbed result and
     *  records the page argument it was called with. */
    private class RecordingFetch {
        private val results = ArrayDeque<FictionResult<ListPage<FictionSummary>>>()
        val pageArgs = mutableListOf<Int>()
        var callCount = 0
            private set

        fun queue(vararg responses: FictionResult<ListPage<FictionSummary>>) {
            results.addAll(responses.toList())
        }

        operator fun invoke(): suspend (Int) -> FictionResult<ListPage<FictionSummary>> = { p ->
            callCount++
            pageArgs += p
            results.removeFirstOrNull()
                ?: error("RecordingFetch: no stubbed result for page=$p (call #$callCount)")
        }
    }

    // -- happy path: items, paging, hasMore --------------------------------------

    @Test fun `loadNext on empty paginator fetches page 1 and accumulates items`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(page(listOf(summary("a"), summary("b")), page = 1, hasNext = true))
        }
        val p = RealBrowsePaginator(fetch())

        assertEquals(emptyList<Any>(), p.items.first())
        p.loadNext()

        val items = p.items.first()
        assertEquals(listOf("a", "b"), items.map { it.id })
        assertEquals(listOf(1), fetch.pageArgs)
        assertEquals(true, p.hasMore.first())
    }

    @Test fun `loadNext appends subsequent pages and bumps page counter`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(
                page(listOf(summary("a")), page = 1, hasNext = true),
                page(listOf(summary("b"), summary("c")), page = 2, hasNext = true),
                page(listOf(summary("d")), page = 3, hasNext = false),
            )
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        p.loadNext()
        p.loadNext()

        assertEquals(listOf("a", "b", "c", "d"), p.items.first().map { it.id })
        assertEquals(listOf(1, 2, 3), fetch.pageArgs)
        assertEquals(false, p.hasMore.first())
    }

    @Test fun `loadNext is a no-op once hasMore flips to false`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(page(listOf(summary("a")), page = 1, hasNext = false))
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertEquals(false, p.hasMore.first())

        // Subsequent loadNext calls must not invoke fetch again.
        p.loadNext()
        p.loadNext()

        assertEquals(1, fetch.callCount)
    }

    // -- isLoading vs isAppending ----------------------------------------------

    @Test fun `first page sets isLoading, subsequent pages set isAppending`() = runTest {
        // Use a CompletableDeferred to inspect intermediate state DURING fetch.
        val gate = CompletableDeferred<FictionResult<ListPage<FictionSummary>>>()
        val p = RealBrowsePaginator { gate.await() }

        val job = launch { p.loadNext() }
        runCurrent()
        // First page in flight: isLoading=true, isAppending=false.
        assertEquals(true, p.isLoading.first())
        assertEquals(false, p.isAppending.first())

        gate.complete(page(listOf(summary("a")), page = 1, hasNext = true))
        job.join()
        assertEquals(false, p.isLoading.first())
        assertEquals(false, p.isAppending.first())

        // Second page: a fresh gate so we can see in-flight state.
        val gate2 = CompletableDeferred<FictionResult<ListPage<FictionSummary>>>()
        val p2 = RealBrowsePaginator { p ->
            if (p == 1) page(listOf(summary("a")), 1, true)
            else gate2.await()
        }
        p2.loadNext() // page 1 returns synchronously
        val job2 = launch { p2.loadNext() }
        runCurrent()
        assertEquals(false, p2.isLoading.first())
        assertEquals(true, p2.isAppending.first())
        gate2.complete(page(listOf(summary("b")), 2, false))
        job2.join()
        assertEquals(false, p2.isAppending.first())
    }

    // -- error path -------------------------------------------------------------

    @Test fun `Failure surfaces error and does NOT bump page counter`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(
                FictionResult.NetworkError("boom"),
                page(listOf(summary("a")), page = 1, hasNext = true),
            )
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertEquals("boom", p.error.first())
        assertEquals(emptyList<Any>(), p.items.first())
        assertEquals(true, p.hasMore.first()) // stays true — we never got a definitive answer

        // Retry: same page number is requested.
        p.loadNext()
        assertEquals(listOf(1, 1), fetch.pageArgs)
        assertEquals(listOf("a"), p.items.first().map { it.id })
        assertNull(p.error.first())
    }

    @Test fun `subsequent Success after Failure clears the error message`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(
                FictionResult.RateLimited(retryAfter = null, message = "slow down"),
                page(listOf(summary("a")), page = 1, hasNext = false),
            )
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertEquals("slow down", p.error.first())

        p.loadNext()
        assertNull(p.error.first())
    }

    // -- #1588 authRequired axis ------------------------------------------------

    @Test fun `AuthRequired failure raises authRequired alongside the error`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(FictionResult.AuthRequired())
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertTrue("AuthRequired must set authRequired", p.authRequired.first())
        assertNotNull("AuthRequired still surfaces an error string", p.error.first())
    }

    @Test fun `non-auth Failure leaves authRequired false`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(FictionResult.NetworkError("boom"))
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertEquals(false, p.authRequired.first())
        assertNotNull(p.error.first())
    }

    @Test fun `Success after AuthRequired clears the authRequired flag`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(
                FictionResult.AuthRequired(),
                page(listOf(summary("a")), page = 1, hasNext = false),
            )
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertTrue(p.authRequired.first())

        p.loadNext()
        assertEquals(false, p.authRequired.first())
    }

    @Test fun `refresh clears the authRequired flag`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(FictionResult.AuthRequired())
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertTrue(p.authRequired.first())

        p.refresh()
        assertEquals(false, p.authRequired.first())
    }

    @Test fun `loading flags are cleared in finally even on Failure`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(FictionResult.NetworkError("boom"))
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()

        assertEquals(false, p.isLoading.first())
        assertEquals(false, p.isAppending.first())
    }

    // -- mutex / concurrent calls ----------------------------------------------

    @Test fun `concurrent loadNext calls are serialized — only one fetch fires`() = runTest {
        val gate = CompletableDeferred<FictionResult<ListPage<FictionSummary>>>()
        var concurrentCalls = 0
        var maxConcurrent = 0

        val p = RealBrowsePaginator { _ ->
            concurrentCalls++
            maxConcurrent = maxOf(maxConcurrent, concurrentCalls)
            try {
                gate.await()
            } finally {
                concurrentCalls--
            }
        }

        // Fire 5 concurrent loadNext calls; the mutex + the isLoading/isAppending
        // guard should mean the first wins and the rest no-op out.
        val jobs = List(5) { async { p.loadNext() } }
        runCurrent()

        gate.complete(page(listOf(summary("a")), 1, false))
        jobs.forEach { it.await() }

        assertEquals("only the first concurrent loadNext should fire fetch", 1, maxConcurrent)
        assertEquals(listOf("a"), p.items.first().map { it.id })
    }

    // -- refresh ---------------------------------------------------------------

    @Test fun `refresh clears items, error, and resets to page 1`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(
                page(listOf(summary("a"), summary("b")), 1, true),
                page(listOf(summary("c")), 2, false),
                page(listOf(summary("a2")), 1, true),
            )
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        p.loadNext()
        assertEquals(3, p.items.first().size) // 2 from page 1 + 1 from page 2
        assertEquals(false, p.hasMore.first())

        p.refresh()
        assertEquals(emptyList<Any>(), p.items.first())
        assertEquals(true, p.hasMore.first())
        assertNull(p.error.first())

        p.loadNext()
        // After refresh, fetch is called with page=1 again.
        assertEquals(listOf(1, 2, 1), fetch.pageArgs)
        assertEquals(listOf("a2"), p.items.first().map { it.id })
    }

    @Test fun `refresh after a Failure clears the error message`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(FictionResult.NetworkError("flaky"))
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        assertNotNull(p.error.first())

        p.refresh()
        assertNull(p.error.first())
    }

    // -- toUiFiction mapper ----------------------------------------------------

    @Test fun `toUiFiction maps fields with sane defaults for nullable inputs`() {
        val s = FictionSummary(
            id = "id1", sourceId = "rr", title = "Title",
            author = "Author",
            coverUrl = null,
            description = null,
            tags = emptyList(),
            status = FictionStatus.ONGOING,
            chapterCount = null,
            rating = null,
        )
        val ui = toUiFiction(s)
        assertEquals("id1", ui.id)
        assertEquals("Title", ui.title)
        assertEquals("Author", ui.author)
        assertNull(ui.coverUrl)
        assertEquals(0f, ui.rating, 0f)
        assertEquals(0, ui.chapterCount)
        assertEquals(true, ui.isOngoing)
        assertEquals("", ui.synopsis)
    }

    @Test fun `toUiFiction maps non-ONGOING status to isOngoing=false`() {
        listOf(
            FictionStatus.COMPLETED,
            FictionStatus.HIATUS,
            FictionStatus.STUB,
            FictionStatus.DROPPED,
        ).forEach { s ->
            assertEquals(
                "status=$s should map to isOngoing=false",
                false,
                toUiFiction(summary("x").copy(status = s)).isOngoing,
            )
        }
    }

    @Test fun `toUiFiction passes rating and chapterCount when present`() {
        val s = summary("x").copy(rating = 4.7f, chapterCount = 42)
        val ui = toUiFiction(s)
        assertEquals(4.7f, ui.rating, 0f)
        assertEquals(42, ui.chapterCount)
    }

    // -- Identity preservation through items map ------------------------------

    @Test fun `loadNext preserves accumulated items across pages (no rewrites)`() = runTest {
        val fetch = RecordingFetch().apply {
            queue(
                page(listOf(summary("a")), 1, true),
                page(listOf(summary("b")), 2, false),
            )
        }
        val p = RealBrowsePaginator(fetch())

        p.loadNext()
        val firstSnapshot = p.items.first()
        p.loadNext()
        val secondSnapshot = p.items.first()

        // The first item should be the same instance after page 2 (no recreate).
        assertSame(
            "page-2 fetch must not rebuild page-1 items",
            firstSnapshot[0],
            secondSnapshot[0],
        )
        assertEquals("b", secondSnapshot[1].id)
    }

    // -- defensive: dispatcher handling ----------------------------------------

    @Test fun `advanceUntilIdle drives the launched fetch to completion`() = runTest {
        // Verifies that the paginator's withLock + suspend fetch interplay
        // doesn't park the test scheduler indefinitely. Single-shot smoke
        // test for the "background scope drives to completion under runTest"
        // contract this whole suite relies on.
        val fetch = RecordingFetch().apply {
            queue(page(listOf(summary("a")), 1, false))
        }
        val p = RealBrowsePaginator(fetch())

        val job = launch { p.loadNext() }
        advanceUntilIdle()
        assertTrue("launched loadNext must complete", job.isCompleted)
    }
}
