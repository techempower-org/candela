package `in`.jphe.storyvox.playback.tts.source

import android.app.Application
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheConfig
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.cache.PCM_SILENCE_TOLERANCE_BYTES
import `in`.jphe.storyvox.playback.cache.PcmIndex
import `in`.jphe.storyvox.playback.cache.PcmIndexEntry
import `in`.jphe.storyvox.playback.cache.isPcmBufferSilent
import `in`.jphe.storyvox.playback.cache.pcmCacheJson
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileOutputStream

/**
 * PR-E (#86) — read-side semantics for [CacheFileSource].
 *
 * Robolectric-backed because [PcmCache] touches `Context.cacheDir`
 * (same shape as PR-D's [EngineStreamingSourceCacheTeeTest]).
 *
 * Verifies:
 *  - Sequential [CacheFileSource.nextChunk] returns sentences in
 *    order with byte-for-byte equality to what [PcmAppender] wrote.
 *  - [PcmIndexEntry.trailingSilenceMs] propagates to [PcmChunk.trailingSilenceBytes].
 *  - [CacheFileSource.seekToCharOffset] jumps to the correct sentence
 *    (with edge cases: before first, past last, mid-sentence).
 *  - [CacheFileSource.bufferHeadroomMs] reports [Long.MAX_VALUE]
 *    (cached chapters can't underrun).
 *  - [CacheFileSource.close] releases the file descriptor.
 *  - Truncated `.pcm` causes [CacheFileSource.open] to throw
 *    [java.io.IOException] (corrupt cache → re-render, not crash).
 *  - `startSentenceIndex` resumes mid-chapter (post-seek path).
 */
// SDK pin lives module-wide in src/test/resources/robolectric.properties
// (sdk=36) — see issue #1132. compileSdk=37 outpaces Robolectric 4.16.1's
// maxSdkVersion=36, so without that pin every RobolectricTestRunner class
// in core-playback fails to initialize.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheFileSourceTest {

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

    /** Render three sentences with distinct deterministic PCM payloads
     *  so the read-back can verify the right bytes came out. */
    private fun renderCache() {
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(sentences[0], ByteArray(100) { 0xA1.toByte() }, trailingSilenceMs = 350)
        app.appendSentence(sentences[1], ByteArray(80)  { 0xB2.toByte() }, trailingSilenceMs = 200)
        app.appendSentence(sentences[2], ByteArray(120) { 0xC3.toByte() }, trailingSilenceMs = 350)
        app.complete()
    }

    @Test
    fun `sequential nextChunk yields sentences in order with correct bytes`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        val c0 = source.nextChunk()
        assertEquals(0, c0?.sentenceIndex)
        assertEquals(100, c0?.pcm?.size)
        assertArrayEquals(ByteArray(100) { 0xA1.toByte() }, c0?.pcm)

        val c1 = source.nextChunk()
        assertEquals(1, c1?.sentenceIndex)
        assertEquals(80, c1?.pcm?.size)
        assertArrayEquals(ByteArray(80) { 0xB2.toByte() }, c1?.pcm)

        val c2 = source.nextChunk()
        assertEquals(2, c2?.sentenceIndex)
        assertEquals(120, c2?.pcm?.size)
        assertArrayEquals(ByteArray(120) { 0xC3.toByte() }, c2?.pcm)

        // Source exhausted.
        assertNull(source.nextChunk())

        source.close()
    }

    @Test
    fun `trailingSilenceBytes propagates from index`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        // sentence 0: trailingSilenceMs=350 → 22050 Hz mono 16-bit
        // = (22050 * 350 / 1000).toInt() * 2 = 7717 * 2 = 15434 bytes.
        val c0 = source.nextChunk()!!
        assertEquals(15434, c0.trailingSilenceBytes)

        // sentence 1: trailingSilenceMs=200 → (22050*200/1000).toInt()*2
        // = 4410 * 2 = 8820 bytes.
        val c1 = source.nextChunk()!!
        assertEquals(8820, c1.trailingSilenceBytes)

        source.close()
    }

    @Test
    fun `seekToCharOffset jumps to the correct sentence (mid-range)`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        // Seek into char 15 — sentence 0 spans [0,10], sentence 1
        // spans [11,20]. indexOfLast { start <= 15 } → sentence 1
        // (start=11). Matches EngineStreamingSource.seekToCharOffset.
        source.seekToCharOffset(15)
        val c = source.nextChunk()
        assertEquals(1, c?.sentenceIndex)
        assertEquals(80, c?.pcm?.size)
        source.close()
    }

    @Test
    fun `seek before first sentence yields sentence 0`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.seekToCharOffset(-100)
        val c = source.nextChunk()
        assertEquals(0, c?.sentenceIndex)
        source.close()
    }

    @Test
    fun `seek past last sentence yields last sentence then exhausts`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.seekToCharOffset(10_000)
        // Last sentence whose start <= 10000 is sentence 2 (start=21).
        val c = source.nextChunk()
        assertEquals(2, c?.sentenceIndex)
        // Then exhausted.
        assertNull(source.nextChunk())
        source.close()
    }

    @Test
    fun `truncated pcm file fails to open with IOException`() = runBlocking {
        renderCache()
        val pcmFile = cache.pcmFileFor(key)
        // Truncate the .pcm to half its size while leaving the index
        // intact. open() must detect the mismatch via the length check.
        FileOutputStream(pcmFile, true).channel.use { ch ->
            ch.truncate(pcmFile.length() / 2)
        }
        var threw = false
        try {
            CacheFileSource.open(pcmFile = pcmFile, indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(
                "truncation message should mention truncated, got: ${e.message}",
                e.message?.contains("truncated") == true,
            )
        }
        assertTrue("CacheFileSource.open should throw on truncated pcm", threw)
    }

    /** Hand-write an index sidecar + matching pcm file, bypassing
     *  [PcmAppender] (whose #1128 guard now refuses to produce a
     *  degenerate entry). Lets these tests forge the exact corrupt
     *  on-disk shapes a killed/disk-full/reaped render can leave behind. */
    private fun writeIndex(index: PcmIndex, pcmBytes: ByteArray) {
        cache.indexFileFor(key)
            .writeText(pcmCacheJson.encodeToString(PcmIndex.serializer(), index))
        FileOutputStream(cache.pcmFileFor(key)).use { it.write(pcmBytes) }
    }

    @Test
    fun `degenerate zero-sentence index fails to open with IOException`() = runBlocking {
        // Issue #1128 root cause — a "complete" entry (idx.json present, so
        // PcmCache.isComplete is true) whose render produced no audio:
        // sentenceCount=0, totalBytes=0, no pcm bytes. Pre-fix this passed
        // the only length check (0 < 0 is false) and CacheFileSource served
        // zero chunks — an instant natural-end that silently skipped the
        // chapter. open() must now reject it so EnginePlayer re-renders.
        writeIndex(
            PcmIndex(sampleRate = 22050, sentenceCount = 0, totalBytes = 0L, sentences = emptyList()),
            ByteArray(0),
        )
        var threw = false
        try {
            CacheFileSource.open(pcmFile = cache.pcmFileFor(key), indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(
                "message should flag degenerate entry, got: ${e.message}",
                e.message?.contains("degenerate") == true,
            )
        }
        assertTrue("open should reject a zero-sentence index (#1128)", threw)
    }

    @Test
    fun `all-silence pcm fails to open with IOException`() = runBlocking {
        // Issue #1281 — a STRUCTURALLY valid entry (correct sentenceCount,
        // totalBytes, contiguous in-bounds offsets) whose .pcm is all
        // zero-amplitude samples. It passes every #1128 structural check, so
        // pre-fix CacheFileSource served pure silence to a clean natural-end
        // and the chapter silently skipped on every play. open() must now
        // reject it via the content gate so EnginePlayer deletes + re-renders.
        writeIndex(
            PcmIndex(
                sampleRate = 22050,
                sentenceCount = 3,
                totalBytes = 300L,
                sentences = listOf(
                    PcmIndexEntry(i = 0, start = 0, end = 10, byteOffset = 0L, byteLen = 100, trailingSilenceMs = 0),
                    PcmIndexEntry(i = 1, start = 11, end = 20, byteOffset = 100L, byteLen = 100, trailingSilenceMs = 0),
                    PcmIndexEntry(i = 2, start = 21, end = 30, byteOffset = 200L, byteLen = 100, trailingSilenceMs = 0),
                ),
            ),
            ByteArray(300), // all zeros == digital silence
        )
        var threw = false
        try {
            CacheFileSource.open(pcmFile = cache.pcmFileFor(key), indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(
                "message should flag silence, got: ${e.message}",
                e.message?.contains("silence") == true,
            )
        }
        assertTrue("open should reject an all-silence entry so it re-renders (#1281)", threw)
    }

    @Test
    fun `mostly-silent but audible pcm still opens`() = runBlocking {
        // Issue #1281 no-over-reject: a real chapter can be mostly quiet yet
        // carry genuine speech. One audible sentence among silent ones puts the
        // non-zero byte count well past the tolerance, so the content gate must
        // KEEP the entry (only an essentially all-zero render is rejected).
        val pcm = ByteArray(300) // sentences 0 and 2 silent...
        for (j in 100 until 200) pcm[j] = 0x7F // ...sentence 1 is audible
        writeIndex(
            PcmIndex(
                sampleRate = 22050,
                sentenceCount = 3,
                totalBytes = 300L,
                sentences = listOf(
                    PcmIndexEntry(i = 0, start = 0, end = 10, byteOffset = 0L, byteLen = 100, trailingSilenceMs = 0),
                    PcmIndexEntry(i = 1, start = 11, end = 20, byteOffset = 100L, byteLen = 100, trailingSilenceMs = 0),
                    PcmIndexEntry(i = 2, start = 21, end = 30, byteOffset = 200L, byteLen = 100, trailingSilenceMs = 0),
                ),
            ),
            pcm,
        )
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(0, source.nextChunk()?.sentenceIndex)
        source.close()
    }

    @Test
    fun `isPcmBufferSilent honors the tolerance`() {
        // Pure helper (#1281): all-zero is silence; real audio is not; the
        // boundary is the tolerance.
        assertTrue("all-zero buffer is silence", isPcmBufferSilent(ByteArray(1000)))
        assertFalse("audible buffer is not silence", isPcmBufferSilent(ByteArray(1000) { 0x40 }))
        val atTolerance = ByteArray(1000).also { b -> for (j in 0 until PCM_SILENCE_TOLERANCE_BYTES) b[j] = 1 }
        assertTrue("<= tolerance non-zero bytes still counts as silence", isPcmBufferSilent(atTolerance))
        val overTolerance = ByteArray(1000).also { b -> for (j in 0..PCM_SILENCE_TOLERANCE_BYTES) b[j] = 1 }
        assertFalse("> tolerance non-zero bytes is not silence", isPcmBufferSilent(overTolerance))
    }

    @Test
    fun `missing pcm file fails to open with IOException`() = runBlocking {
        // Issue #1128 — the idx.json survived an eviction / chapter sweep
        // that failed to unlink its .pcm sibling (PcmCache.delete/evictTo
        // swallow per-file errors). isComplete stays true but the audio is
        // gone; open() must reject rather than serve an empty file.
        renderCache()
        assertTrue("precondition: pcm exists", cache.pcmFileFor(key).delete())
        var threw = false
        try {
            CacheFileSource.open(pcmFile = cache.pcmFileFor(key), indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(
                "message should flag missing pcm, got: ${e.message}",
                e.message?.contains("missing") == true,
            )
        }
        assertTrue("open should reject a missing .pcm (#1128)", threw)
    }

    @Test
    fun `index entry overrunning the pcm file fails to open`() = runBlocking {
        // Issue #1128 — a corrupt index whose totalBytes matches the file
        // length but whose last entry references bytes past EOF. The mmap /
        // RandomAccessFile read would silently return short/garbage for that
        // sentence; reject it up front.
        val pcm = ByteArray(100) { 0x44 }
        writeIndex(
            PcmIndex(
                sampleRate = 22050,
                sentenceCount = 1,
                totalBytes = 100L,
                // byteOffset 60 + byteLen 80 = 140 > 100-byte file.
                sentences = listOf(PcmIndexEntry(i = 0, start = 0, end = 10, byteOffset = 60L, byteLen = 80, trailingSilenceMs = 0)),
            ),
            pcm,
        )
        var threw = false
        try {
            CacheFileSource.open(pcmFile = cache.pcmFileFor(key), indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(
                "message should flag overrun, got: ${e.message}",
                e.message?.contains("overruns") == true,
            )
        }
        assertTrue("open should reject an index that overruns the file (#1128)", threw)
    }

    @Test
    fun `unreadable index json fails to open with IOException not SerializationException`() = runBlocking {
        // Issue #1128 — a truncated / half-written idx.json (disk-full
        // finalize, OS reap mid-write) decodes to a SerializationException,
        // which is NOT the IOException EnginePlayer's recovery catch keyed
        // on pre-fix. open() must surface it as IOException so the entry is
        // invalidated + re-rendered rather than escaping recovery.
        cache.indexFileFor(key).writeText("{ this is not valid json")
        FileOutputStream(cache.pcmFileFor(key)).use { it.write(ByteArray(100)) }
        var threw = false
        try {
            CacheFileSource.open(pcmFile = cache.pcmFileFor(key), indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(
                "message should flag unreadable index, got: ${e.message}",
                e.message?.contains("unreadable") == true,
            )
        }
        assertTrue("open should wrap a bad-JSON index as IOException (#1128)", threw)
    }

    @Test
    fun `bufferHeadroomMs reports MAX_VALUE for cache source`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(Long.MAX_VALUE, source.bufferHeadroomMs.value)
        // Pull a chunk; headroom unchanged (cache is never underrunning).
        source.nextChunk()
        assertEquals(Long.MAX_VALUE, source.bufferHeadroomMs.value)
        source.close()
    }

    @Test
    fun `producer queue depth and capacity report zero for cache source`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(0, source.producerQueueDepth())
        assertEquals(0, source.producerQueueCapacity())
        source.close()
    }

    @Test
    fun `close releases file descriptor allowing delete`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.close()
        // After close, the .pcm file should be deletable on Linux
        // (the only platform we run JVM tests on). Windows holds
        // file locks differently but we don't ship there.
        val deleted = cache.pcmFileFor(key).delete()
        assertTrue("file delete after close should succeed", deleted)
    }

    @Test
    fun `start sentence index defaults to zero`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            // no startSentenceIndex param
        )
        val c = source.nextChunk()
        assertEquals(0, c?.sentenceIndex)
        source.close()
    }

    @Test
    fun `start sentence index resumes mid-chapter`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            startSentenceIndex = 1,
        )
        val c = source.nextChunk()
        assertEquals(1, c?.sentenceIndex)
        assertEquals(80, c?.pcm?.size)
        source.close()
    }

    @Test
    fun `start sentence index past end exhausts immediately`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            startSentenceIndex = 99,
        )
        // The cursor is coerced into [0, sentences.size]; passing
        // size means "exhausted on first call" without throwing.
        assertNull(source.nextChunk())
        source.close()
    }

    @Test
    fun `finalizeCache on cache source is a no-op`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        // finalizeCache is the natural-end branch in EnginePlayer's
        // consumer; on cache source it must be a no-op (the file is
        // already finalized — that's the precondition for opening it).
        // Verify by calling and then checking the index is unchanged.
        val idxBytesBefore = cache.indexFileFor(key).readBytes()
        source.finalizeCache()
        val idxBytesAfter = cache.indexFileFor(key).readBytes()
        assertArrayEquals(idxBytesBefore, idxBytesAfter)
        source.close()
    }

    @Test
    fun `pcm sample rate is read from index`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(22050, source.sampleRate)
        source.close()
    }

    @Test
    fun `sentence ranges round-trip through the chunk`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        val c0 = source.nextChunk()!!
        // Sentence 0 was Sentence(0, 0, 10, "First.") — the index
        // entry's start/end propagate into the chunk's range.
        assertEquals(0, c0.range.sentenceIndex)
        assertEquals(0, c0.range.startCharInChapter)
        assertEquals(10, c0.range.endCharInChapter)
        source.close()
    }
}
