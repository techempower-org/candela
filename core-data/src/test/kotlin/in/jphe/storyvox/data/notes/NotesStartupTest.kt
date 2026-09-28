package `in`.jphe.storyvox.data.notes

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice Notes (#1657) — the startup seam for the orphan-audio sweep: runs
 * exactly once per process, on the injected dispatcher, and never lets a
 * failure escape into the (handler-less) app init scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesStartupTest {

    @Test
    fun start_runsTheSweepOnce_onTheInjectedDispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val startup = NotesStartup(sweep = { calls++; 2 }, dispatcher = dispatcher, onError = {})

        assertNotNull("first start launches", startup.start(this))
        assertEquals("nothing runs inline on the caller", 0, calls)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun start_twice_isANoOp() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val startup = NotesStartup(sweep = { calls++; 0 }, dispatcher = dispatcher, onError = {})

        startup.start(this)
        assertNull("second start is a no-op", startup.start(this))
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun start_sweepThrows_isCaughtAndReported_scopeSurvives() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        // Mirror StoryvoxApp.initScope: a SupervisorJob and NO exception handler.
        val initLike = CoroutineScope(SupervisorJob() + dispatcher)
        val errors = mutableListOf<Throwable>()
        val startup = NotesStartup(
            sweep = { throw IOException("disk gone") },
            dispatcher = dispatcher,
            onError = { errors += it },
        )

        val job = startup.start(initLike)!!
        scope.advanceUntilIdle()

        assertTrue("job completed", job.isCompleted)
        assertTrue("completed normally, not by exception", !job.isCancelled)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IOException)
    }

    @Test
    fun start_onErrorItselfThrows_stillDoesNotEscape() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val initLike = CoroutineScope(SupervisorJob() + dispatcher)
        val startup = NotesStartup(
            sweep = { error("boom") },
            dispatcher = dispatcher,
            onError = { throw IllegalStateException("logger broke") },
        )

        val job = startup.start(initLike)!!
        scope.advanceUntilIdle()

        assertTrue(job.isCompleted && !job.isCancelled)
    }
}
