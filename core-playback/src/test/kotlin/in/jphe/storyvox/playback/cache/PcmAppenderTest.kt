package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.playback.tts.Sentence
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PcmAppenderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pcm-appender-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newAppender(basename: String = "abc"): PcmAppender = PcmAppender(
        pcmFile = File(dir, "$basename.pcm"),
        metaFile = File(dir, "$basename.meta.json"),
        indexFile = File(dir, "$basename.idx.json"),
        sampleRate = 22050,
        chapterId = "skypride/ch1",
        voiceId = "cori",
        chunkerVersion = 1,
        speedHundredths = 100,
        pitchHundredths = 100,
    )

    @Test
    fun `append two sentences then finalize produces correct files`() {
        val app = newAppender()
        val s0 = Sentence(0, 0, 10, "First.")
        val s1 = Sentence(1, 11, 20, "Second.")
        val pcm0 = ByteArray(100) { 0x11 }
        val pcm1 = ByteArray(50)  { 0x22 }

        app.appendSentence(s0, pcm0, trailingSilenceMs = 350)
        app.appendSentence(s1, pcm1, trailingSilenceMs = 350)
        app.complete()

        // pcm = concat of both
        val pcmRead = File(dir, "abc.pcm").readBytes()
        assertEquals(150, pcmRead.size)
        assertArrayEquals(pcm0, pcmRead.copyOfRange(0, 100))
        assertArrayEquals(pcm1, pcmRead.copyOfRange(100, 150))

        // idx.json present + correct
        val idxText = File(dir, "abc.idx.json").readText()
        val idx = pcmCacheJson.decodeFromString(PcmIndex.serializer(), idxText)
        assertEquals(22050, idx.sampleRate)
        assertEquals(2, idx.sentenceCount)
        assertEquals(150L, idx.totalBytes)
        assertEquals(0L, idx.sentences[0].byteOffset)
        assertEquals(100, idx.sentences[0].byteLen)
        assertEquals(100L, idx.sentences[1].byteOffset)
        assertEquals(50, idx.sentences[1].byteLen)
        assertEquals(0, idx.sentences[0].start)
        assertEquals(11, idx.sentences[1].start)
        assertEquals(350, idx.sentences[0].trailingSilenceMs)

        // meta.json present + carries chapterId
        val metaText = File(dir, "abc.meta.json").readText()
        val meta = pcmCacheJson.decodeFromString(PcmMeta.serializer(), metaText)
        assertEquals("skypride/ch1", meta.chapterId)
        assertEquals("cori", meta.voiceId)
        assertEquals(22050, meta.sampleRate)
        assertEquals(1, meta.chunkerVersion)
    }

    @Test
    fun `abandon deletes all three files`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(20), trailingSilenceMs = 350)
        // pcm + meta exist after construction+append; idx will not yet
        assertTrue(File(dir, "abc.pcm").exists())
        assertTrue(File(dir, "abc.meta.json").exists())
        assertFalse(File(dir, "abc.idx.json").exists())

        app.abandon()

        assertFalse(File(dir, "abc.pcm").exists())
        assertFalse(File(dir, "abc.meta.json").exists())
        assertFalse(File(dir, "abc.idx.json").exists())
    }

    @Test
    fun `empty pcm is skipped silently`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(0), trailingSilenceMs = 350)
        app.appendSentence(Sentence(1, 11, 20, "There."), ByteArray(40) { 0x33 }, trailingSilenceMs = 350)
        app.complete()

        val idx = pcmCacheJson.decodeFromString(
            PcmIndex.serializer(),
            File(dir, "abc.idx.json").readText(),
        )
        // Only the 40-byte sentence made it
        assertEquals(1, idx.sentenceCount)
        assertEquals(40L, idx.totalBytes)
        assertEquals(1, idx.sentences[0].i)
    }

    @Test
    fun `complete with no appended sentences discards the entry`() {
        // Issue #1128 — a render that never appended a sentence (every
        // sentence produced empty/declined PCM) must NOT finalize a
        // "complete" but zero-byte entry. Such an entry makes
        // PcmCache.isComplete return true while CacheFileSource serves zero
        // audio, so the chapter is silently skipped on every play until the
        // user clears the cache by hand. complete() discards instead.
        val app = newAppender()
        // No appendSentence calls at all.
        app.complete()

        assertFalse(
            "complete() with no sentences must not write idx.json (#1128)",
            File(dir, "abc.idx.json").exists(),
        )
        // The degenerate partial (pcm + meta written at construction) is
        // wiped too, so isComplete is false → next play re-renders.
        assertFalse(File(dir, "abc.pcm").exists())
        assertFalse(File(dir, "abc.meta.json").exists())
    }

    @Test
    fun `complete after only empty-pcm sentences discards the entry`() {
        // Issue #1128 — same poison, reached via the appender's empty-PCM
        // skip path: every sentence the engine returned was empty, so
        // nothing was recorded in the index. Finalizing would still land a
        // zero-sentence idx.json. Discard instead.
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(0), trailingSilenceMs = 350)
        app.appendSentence(Sentence(1, 11, 20, "There."), ByteArray(0), trailingSilenceMs = 350)
        app.complete()

        assertFalse(
            "all-empty render must not write idx.json (#1128)",
            File(dir, "abc.idx.json").exists(),
        )
        assertFalse(File(dir, "abc.pcm").exists())
        assertFalse(File(dir, "abc.meta.json").exists())
    }

    @Test
    fun `finalize after abandon throws`() {
        val app = newAppender()
        app.abandon()
        var threw = false
        try { app.complete() } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `append after finalize throws`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(10), trailingSilenceMs = 350)
        app.complete()
        var threw = false
        try {
            app.appendSentence(Sentence(1, 11, 20, "Bye."), ByteArray(10), trailingSilenceMs = 350)
        } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `PcmAppender no longer declares its own finalize method`() {
        // Issue #581 — the GC's `FinalizerDaemon` invokes
        // `Object.finalize()` on every reclaimed instance. PcmAppender
        // historically had its own `fun finalize()` that silently
        // shadowed Object's hook (Kotlin doesn't require the `override`
        // keyword for the deprecated GC hook). When the GC reclaimed a
        // leaked already-completed appender it re-invoked the same
        // method and the `check(!closed)` precondition threw an
        // uncaught IllegalStateException on the finalizer thread.
        //
        // The fix renamed `finalize()` → `complete()`. We pin the
        // structural contract here: `PcmAppender` does not declare a
        // `finalize` method on its own class. The inherited
        // Object.finalize() is a no-op and module-protected; the GC
        // can still call it (and on Android it does, every GC cycle
        // an object is reclaimed), but it can never throw because
        // we no longer override it.
        val ownMethods = PcmAppender::class.java.declaredMethods
        val finalizeOwn = ownMethods.firstOrNull { it.name == "finalize" }
        assertTrue(
            "PcmAppender must not declare a finalize() method (Issue #581); " +
                "found: $finalizeOwn",
            finalizeOwn == null,
        )
        // And the rename is in place — `complete` IS a declared method.
        val completeOwn = ownMethods.firstOrNull { it.name == "complete" }
        assertTrue(
            "PcmAppender must declare a complete() method after the #581 rename",
            completeOwn != null,
        )
    }
}
