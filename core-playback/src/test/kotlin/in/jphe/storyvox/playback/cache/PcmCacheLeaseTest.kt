package `in`.jphe.storyvox.playback.cache

import android.app.Application
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue #1034 — the cross-boundary mutual exclusion that [PcmCache]'s
 * kdoc promised ("at most one PcmAppender open per key ... PR-D enforces
 * that mutual exclusion at the streaming-source / render-job boundary")
 * was never implemented. The background [ChapterRenderJob] and the
 * foreground streaming-tee could open two appenders on the SAME cache key
 * and interleave append-mode writes / delete files under each other's open
 * stream, finalizing a corrupt entry.
 *
 * These tests pin the lease-based single-owner arbitration that closes the
 * race: a key has at most one live writer; the foreground wins over a
 * background worker; and a writer that lost the key (evicted, or refused at
 * open time) cannot clobber the winner's entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PcmCacheLeaseTest {

    private lateinit var context: Application
    private lateinit var cache: PcmCache
    private lateinit var config: PcmCacheConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = PcmCacheConfig(context)
        cache = PcmCache(context, config)
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private val key = PcmCacheKey("ch1", "cori", 100, 100, 1, 0)

    private fun sentence() = Sentence(0, 0, 10, "Sentence.")

    @Test
    fun `worker is refused while the foreground holds the key`() = runBlocking {
        val fg = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)
        assertNotNull("foreground must acquire a free key", fg)

        val worker = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.WORKER)
        assertNull("worker must be refused while foreground owns the key", worker)

        fg!!.abandon()
    }

    @Test
    fun `foreground evicts a worker that already holds the key`() = runBlocking {
        val worker = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.WORKER)
        assertNotNull("worker acquires a free key", worker)

        val fg = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)
        assertNotNull("foreground must win the key from a worker", fg)

        // The evicted worker lease is no longer active — its lifecycle calls
        // must be inert so it can't corrupt the foreground entry.
        assertFalse("worker lease must report inactive after eviction", worker!!.isActive)

        fg!!.abandon()
    }

    @Test
    fun `an evicted worker cannot clobber the foreground entry`() = runBlocking {
        // Worker opens first and writes one sentence.
        val worker = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.WORKER)!!
        worker.appendSentence(sentence(), ByteArray(100) { 0x11 }, trailingSilenceMs = 0)

        // Foreground takes over the key (foreground-wins) and renders the
        // real entry to completion.
        val fg = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)!!
        fg.appendSentence(sentence(), ByteArray(200) { 0x22 }, trailingSilenceMs = 0)

        // The evicted worker keeps trying to write + finalize (it doesn't
        // know it lost). These must be no-ops, NOT writes to the shared file.
        worker.appendSentence(sentence(), ByteArray(100) { 0x11 }, trailingSilenceMs = 0)
        worker.complete()

        // Foreground finalizes the genuine entry.
        fg.complete()

        assertTrue("foreground entry must be complete", cache.isComplete(key))
        // The pcm must contain ONLY the foreground's bytes (200), never the
        // worker's interleaved/garbage writes.
        assertEquals(
            "pcm must hold exactly the foreground's bytes, no worker interleave",
            200L,
            cache.pcmFileFor(key).length(),
        )
    }

    @Test
    fun `releasing the key lets the next owner acquire it`() = runBlocking {
        val first = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)!!
        first.appendSentence(sentence(), ByteArray(50), trailingSilenceMs = 0)
        first.complete()

        // After complete() the key is released — a worker may now re-acquire
        // (e.g. to re-render after the entry was later deleted).
        cache.delete(key)
        val second = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.WORKER)
        assertNotNull("a released key must be re-acquirable", second)
        second!!.abandon()
    }
}
