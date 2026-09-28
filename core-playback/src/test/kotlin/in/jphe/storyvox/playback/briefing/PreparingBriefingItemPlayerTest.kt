package `in`.jphe.storyvox.playback.briefing

import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1810 — a briefing item must be prepared the way the Play buttons prepare a
 * chapter (service, download, wait) before play(); a bare play() on a cold
 * launch does nothing.
 */
class PreparingBriefingItemPlayerTest {

    private class Harness(
        var engineBound: Boolean = false,
        var bodyReady: Boolean = true,
        var engineBinds: Boolean = true,
        val serviceStartThrows: Boolean = false,
    ) {
        val calls = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val player = PreparingBriefingItemPlayer(
            isEngineBound = { engineBound },
            startService = {
                calls += "startService"
                if (serviceStartThrows) throw IllegalStateException("not allowed from the background")
            },
            queueDownload = { f, c -> calls += "download($f,$c)" },
            awaitBody = { c -> calls += "awaitBody($c)"; bodyReady },
            awaitEngine = { calls += "awaitEngine"; engineBinds },
            play = { f, c -> calls += "play($f,$c)" },
            onProblem = { problems += it },
        )
    }

    @Test fun `a cold item starts the service, downloads and waits before playing`() = runTest {
        val h = Harness(engineBound = false)
        h.player.playItem("arxiv:1", "arxiv:1:c")
        assertEquals(
            listOf("startService", "download(arxiv:1,arxiv:1:c)", "awaitBody(arxiv:1:c)", "awaitEngine", "play(arxiv:1,arxiv:1:c)"),
            h.calls,
        )
    }

    @Test fun `a bound engine is not sent another service start`() = runTest {
        val h = Harness(engineBound = true)
        h.player.playItem("f", "c")
        assertEquals(listOf("download(f,c)", "awaitBody(c)", "awaitEngine", "play(f,c)"), h.calls)
    }

    @Test fun `a body that never arrives plays nothing and says why`() = runTest {
        val h = Harness(bodyReady = false)
        h.player.playItem("f", "c")
        assertTrue("no play() without a body", h.calls.none { it.startsWith("play") })
        assertEquals(1, h.problems.size)
    }

    @Test fun `no engine binding plays nothing and says why`() = runTest {
        val h = Harness(engineBinds = false)
        h.player.playItem("f", "c")
        assertTrue(h.calls.none { it.startsWith("play") })
        assertEquals(1, h.problems.size)
    }

    @Test fun `play runs on the play context, not the caller's thread`() = runTest {
        // The app's playContext is Main; stand in with a named single thread.
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
        try {
            var playThread: String? = null
            val player = PreparingBriefingItemPlayer(
                isEngineBound = { true },
                startService = {},
                queueDownload = { _, _ -> },
                awaitBody = { true },
                awaitEngine = { true },
                play = { _, _ -> playThread = Thread.currentThread().name },
                playContext = main.asCoroutineDispatcher(),
            )
            // The queue's auto-advance calls in from Dispatchers.Default.
            withContext(Dispatchers.Default) { player.playItem("f", "c") }
            assertEquals("fake-main", playThread)
        } finally {
            main.shutdown()
        }
    }

    @Test fun `a refused service start is reported, not thrown`() = runTest {
        val h = Harness(serviceStartThrows = true, engineBinds = false)
        h.player.playItem("f", "c") // must not throw
        assertTrue("the refusal is reported", h.problems.any { it.contains("service") })
        assertTrue(h.calls.none { it.startsWith("play") })
    }
}
