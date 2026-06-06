package `in`.jphe.storyvox.playback.tts.source

import android.app.Application
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheConfig
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PR-D (#86) — cache-tee write semantics for [EngineStreamingSource].
 *
 * Robolectric-backed because PcmCache touches `Context.cacheDir` (same
 * shape as PR-C's [`in`.jphe.storyvox.playback.cache.PcmCacheTest]).
 * Verifies the four invariants in the plan:
 *  1. Tee fires for each generated sentence; pcm-file bytes = concat
 *     of engine outputs.
 *  2. finalizeCache lands the index → isComplete = true.
 *  3. close abandons → pcm + meta + idx all gone.
 *  4. seekToCharOffset abandons (sparse-cache prevention).
 *  5. null appender path is a behavioral no-op (pre-PR-D back-compat).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EngineStreamingSourceCacheTeeTest {

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

    private val key = PcmCacheKey(
        chapterId = "ch1",
        voiceId = "cori",
        speedHundredths = 100,
        pitchHundredths = 100,
        chunkerVersion = CHUNKER_VERSION,
        pronunciationDictHash = 0,
    )

    private val sentences = listOf(
        Sentence(0, 0, 10, "First."),
        Sentence(1, 11, 20, "Second."),
        Sentence(2, 21, 30, "Third."),
    )

    /** Engine that returns a deterministic 100-byte PCM per sentence. */
    private fun fakeEngine() = object : EngineStreamingSource.VoiceEngineHandle {
        override val sampleRate: Int = 22050
        override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray =
            ByteArray(100) { 0x42 }
    }

    @Test
    fun `tee writes one cache entry per sentence then finalize completes the cache`() = runBlocking {
        val appender = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)!!
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheLease = appender,
        )

        // Drain the source — pull every sentence + the END_PILL. This
        // is what the consumer thread does in EnginePlayer; the
        // produced bytes also flow into the tee on the producer side.
        var pulled = 0
        while (true) {
            val c = source.nextChunk() ?: break
            pulled++
            assertEquals(100, c.pcm.size)
        }
        assertEquals(3, pulled)

        // Pre-finalize: pcm + meta exist, idx absent.
        assertTrue(cache.pcmFileFor(key).exists())
        assertTrue(cache.metaFileFor(key).exists())
        assertFalse(cache.isComplete(key))

        source.finalizeCache()

        // Post-finalize: idx lands. Cache complete.
        assertTrue(cache.isComplete(key))
        // Total bytes = 3 sentences * 100 bytes
        assertEquals(300L, cache.pcmFileFor(key).length())

        source.close()
    }

    @Test
    fun `close abandons the in-progress cache (no index lands)`() = runBlocking {
        val appender = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)!!
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheLease = appender,
        )

        // Pull two of three sentences so at least one tee fired.
        source.nextChunk()
        source.nextChunk()

        source.close()

        // abandon() deletes the triple. (PR-C contract: abandon
        // unlinks pcm + meta + idx + idx.tmp.)
        assertFalse(cache.pcmFileFor(key).exists())
        assertFalse(cache.metaFileFor(key).exists())
        assertFalse(cache.isComplete(key))
    }

    @Test
    fun `seek abandons cache progress`() = runBlocking {
        val appender = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)!!
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheLease = appender,
        )
        source.nextChunk()                // pull one sentence to ensure
                                          // a tee write hit disk before
                                          // the seek-abandon fires.
        source.seekToCharOffset(20)        // seek into sentence 2

        // Wait briefly for the (cancelled) producer to settle.
        delay(100)

        // Cache files for this key should be gone — abandon ran.
        assertFalse(cache.pcmFileFor(key).exists())
        assertFalse(cache.isComplete(key))
        source.close()
    }

    @Test
    fun `null appender means no cache writes (back-compat)`() = runBlocking {
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheLease = null,        // pre-PR-D behavior
        )
        // Drain all three sentences.
        repeat(3) { source.nextChunk() }
        source.finalizeCache()           // no-op on null

        // No cache files exist for this key.
        assertFalse(cache.pcmFileFor(key).exists())
        assertEquals(0, source.cacheTeeErrors.value)
        source.close()
    }

    @Test
    fun `finalize is idempotent — second call after finalize is a no-op`() = runBlocking {
        val appender = cache.openLease(key, sampleRate = 22050, owner = PcmCache.LeaseOwner.FOREGROUND)!!
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine(),
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            cacheLease = appender,
        )
        // Drain.
        while (source.nextChunk() != null) Unit
        source.finalizeCache()
        assertTrue(cache.isComplete(key))
        // Second finalize: no-op. Doesn't throw, doesn't increment
        // error counter, cache stays complete.
        source.finalizeCache()
        assertTrue(cache.isComplete(key))
        assertEquals(0, source.cacheTeeErrors.value)
        source.close()
    }
}
