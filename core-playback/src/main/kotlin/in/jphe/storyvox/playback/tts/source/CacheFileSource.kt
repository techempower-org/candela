package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.cache.PcmIndex
import `in`.jphe.storyvox.playback.cache.PcmIndexEntry
import `in`.jphe.storyvox.playback.cache.pcmCacheJson
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * PR-E (#86) — reads a finalized PCM cache entry from disk and serves
 * [PcmChunk]s to [`in`.jphe.storyvox.playback.tts.EnginePlayer]'s
 * consumer thread.
 *
 * Constructed by `EnginePlayer.startPlaybackPipeline` when
 * [`in`.jphe.storyvox.playback.cache.PcmCache.isComplete] returns true
 * for the (chapter, voice, speed, pitch, chunkerVersion, dictHash)
 * key. The consumer treats this source identically to
 * [EngineStreamingSource] — pull a chunk, write to AudioTrack, fire
 * UI sentence-transition events. The difference: this source NEVER
 * blocks meaningfully (no engine inference), so [bufferHeadroomMs]
 * stays effectively unbounded and PR-B's pause-buffer-resume UI never
 * fires for cached chapters.
 *
 * **Pacing.** The consumer's `track.write()` blocks once the AudioTrack
 * hardware buffer is full (~2 s of audio at minBufferSize on Tab A7
 * Lite). That's what regulates this source's effective rate — without
 * that back-pressure, the consumer would freerun through the entire
 * cached chapter in milliseconds and the UI would flash sentence
 * boundaries faster than the audio plays.
 *
 * **mmap vs read.** The default path mmap's the entire `.pcm` file
 * via `FileChannel.map(READ_ONLY, 0, len)`. mmap is preferable
 * because the OS page cache absorbs the read cost — sequential mmap
 * access on UFS 2.0 internal flash is >100 MB/s effective. Fallback:
 * some 32-bit emulator kernels reject mmap requests > 1 GB with
 * EINVAL. In that case we fall back to [RandomAccessFile.read] into
 * a [ByteArray] per sentence, which is correctness-equivalent but
 * allocates per chunk. The fallback is detected at construction
 * time and never re-tried mid-playback.
 */
class CacheFileSource private constructor(
    private val pcmFile: File,
    private val index: PcmIndex,
    startSentenceIndex: Int,
    /** True if mmap succeeded; false → fallback to read path. */
    private val mapped: MappedByteBuffer?,
    private val randomAccess: RandomAccessFile,
) : PcmSource {

    override val sampleRate: Int = index.sampleRate

    /** Cursor into [PcmIndex.sentences]. Mutated by [nextChunk] and
     *  [seekToCharOffset]; single-threaded access (the consumer thread). */
    @Volatile private var cursor: Int = startSentenceIndex.coerceIn(0, index.sentences.size)

    private val _bufferHeadroomMs = MutableStateFlow(Long.MAX_VALUE)

    /**
     * Always reports an effectively unbounded headroom. Cache files
     * never underrun — a cached chapter has every sentence already on
     * disk. The streaming-source headroom flow drives PR-B's
     * pause-buffer-resume UI; for the cache source we want that UI to
     * never fire (which is correct), so we report a huge constant.
     */
    override val bufferHeadroomMs: StateFlow<Long> = _bufferHeadroomMs.asStateFlow()

    /** Cache source has no producer queue. Reported as 0/0 to the
     *  Debug overlay (issue #290). */
    override fun producerQueueDepth(): Int = 0
    override fun producerQueueCapacity(): Int = 0

    /** No headroom tracking for cache files — they don't underrun. */
    override fun decrementHeadroomForChunk(chunk: PcmChunk) = Unit

    /**
     * #573 — Gapless: stamped true when [nextChunk] returns null
     * because the cursor walked past the last sentence (i.e. the cache
     * file is fully drained). The consumer reads [producedAllSentences]
     * to know "this is a natural chapter end" without inferring from
     * the racy `pipelineRunning` flag. Volatile because the consumer
     * (audio thread) writes it inside [nextChunk] (which dispatches to
     * Dispatchers.IO) and the consumer's finally block reads it.
     */
    @Volatile private var producedAll: Boolean = false
    override val producedAllSentences: Boolean get() = producedAll

    override suspend fun nextChunk(): PcmChunk? = withContext(Dispatchers.IO) {
        val entries = index.sentences
        val i = cursor
        if (i >= entries.size) {
            // #573 — Gapless: stamp the natural-end flag BEFORE returning
            // null so the consumer's finally block sees a stable
            // "yes, the cache was fully drained" signal independent of
            // any concurrent stopPlaybackPipeline call.
            producedAll = true
            android.util.Log.i(
                "CacheFileSource",
                "cache source: natural end (cursor=$i past last sentence ${entries.size}), " +
                    "returning null",
            )
            return@withContext null
        }
        val e = entries[i]
        cursor = i + 1
        val pcm = readPcmFor(e)
        val silenceBytes = silenceBytesFor(e.trailingSilenceMs, sampleRate)
        PcmChunk(
            sentenceIndex = e.i,
            range = SentenceRange(e.i, e.start, e.end),
            pcm = pcm,
            trailingSilenceBytes = silenceBytes,
        )
    }

    override suspend fun seekToCharOffset(charOffset: Int) {
        // Find the latest sentence whose start <= charOffset. Matches
        // EngineStreamingSource.seekToCharOffset's mapping so both
        // sources behave identically post-seek. Offset before the first
        // sentence → cursor=0; offset past the last sentence → cursor
        // at the last sentence (which then exhausts after one nextChunk).
        val target = index.sentences.indexOfLast { it.start <= charOffset }
            .takeIf { it >= 0 } ?: 0
        cursor = target
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        // #896 — Force-unmap the mmap'd buffer before releasing the file.
        // GC-based Cleaner cleanup is too lazy: each .pcm mapping holds
        // virtual address space (≈80 MB per 30 min chapter) until GC runs,
        // so chapter-heavy sessions accumulate stale VM areas and trend the
        // process toward LMK trim on low-RAM devices. Invoke the
        // DirectByteBuffer cleaner via reflection — a private ART API that
        // may break on future Android JVMs, hence best-effort: if it throws,
        // we fall back to GC cleanup, which is the prior behavior.
        forceUnmap(mapped)
        // Release the underlying RandomAccessFile so the file descriptor
        // is returned to the OS.
        runCatching { randomAccess.close() }
        Unit
    }

    /**
     * Cache source has no in-flight cache write to finalize — the
     * file was finalized before this source was constructed (that's
     * the [`in`.jphe.storyvox.playback.cache.PcmCache.isComplete]
     * precondition). Inherited default would also no-op; override
     * is explicit for documentation.
     */
    override fun finalizeCache() = Unit

    /** Read the bytes for a single sentence's PCM range. mmap path:
     *  slice the MappedByteBuffer view. Read path: position +
     *  read into a fresh ByteArray. */
    private fun readPcmFor(entry: PcmIndexEntry): ByteArray {
        val mapped = this.mapped
        if (mapped != null) {
            // Slice view — no copy of the underlying bytes. We DO need
            // to copy into a ByteArray because EnginePlayer's consumer
            // thread calls `track.write(byteArray, off, len)`, which
            // doesn't accept a ByteBuffer in the byte[] AudioTrack
            // API path. Future optimization: extend PcmChunk to carry
            // a ByteBuffer alongside the byte[]; saves one copy per
            // sentence (~1 ms on Tab A7 Lite, not material).
            val out = ByteArray(entry.byteLen)
            mapped.position(entry.byteOffset.toInt())
            mapped.get(out, 0, entry.byteLen)
            return out
        }
        // Read-path fallback.
        val raf = randomAccess
        val out = ByteArray(entry.byteLen)
        synchronized(raf) {
            raf.seek(entry.byteOffset)
            var read = 0
            while (read < entry.byteLen) {
                val n = raf.read(out, read, entry.byteLen - read)
                if (n < 0) break
                read += n
            }
        }
        return out
    }

    /**
     * #896 — Best-effort force-unmap of a [MappedByteBuffer] via the
     * private `DirectByteBuffer.cleaner()` reflection trick. On ART the
     * direct buffer exposes a no-arg `cleaner()` method returning a
     * `Cleaner` with a no-arg `clean()` that releases the mapping
     * immediately. Both the method lookup and invocation are wrapped in
     * [runCatching]: this is a private API, so any failure (method
     * renamed/removed on a future runtime, null cleaner on a non-direct
     * buffer) silently degrades to the old GC-driven cleanup.
     */
    private fun forceUnmap(buffer: MappedByteBuffer?) {
        buffer ?: return
        runCatching {
            val cleanerMethod = buffer.javaClass.getMethod("cleaner").apply {
                isAccessible = true
            }
            val cleaner = cleanerMethod.invoke(buffer) ?: return
            cleaner.javaClass.getMethod("clean").apply { isAccessible = true }
                .invoke(cleaner)
        }
    }

    companion object {
        /**
         * Open a `CacheFileSource` for an already-finalized cache entry.
         * `EnginePlayer.startPlaybackPipeline` calls this when
         * `PcmCache.isComplete(key)` returns true.
         *
         * @throws IOException if the entry fails its integrity gate (#1128):
         *  unreadable / corrupt index JSON, a degenerate (zero-sentence)
         *  index, a self-inconsistent manifest, a missing `.pcm`, or a
         *  `.pcm` shorter than the index requires. EnginePlayer catches
         *  this, deletes the bad entry, and falls back to the streaming
         *  source — a corrupt cache re-renders rather than silently
         *  skipping the chapter or crashing playback.
         */
        @Throws(IOException::class)
        suspend fun open(
            pcmFile: File,
            indexFile: File,
            startSentenceIndex: Int = 0,
        ): CacheFileSource = withContext(Dispatchers.IO) {
            // Issue #1128 — the index sidecar itself can be corrupt
            // (truncated / half-written JSON from a disk-full finalize or
            // an OS cache-dir reap mid-write). Decoding it then throws a
            // [kotlinx.serialization.SerializationException], NOT the
            // [IOException] this method advertises and EnginePlayer's
            // fall-back-to-streaming catch keys on — so a corrupt index
            // would have escaped the cache-recovery path. Surface it as the
            // documented IOException instead.
            val index = runCatching {
                pcmCacheJson.decodeFromString(PcmIndex.serializer(), indexFile.readText())
            }.getOrElse { t ->
                // #1175 — never rewrap cancellation as IOException. The caller
                // keys its fall-back-to-streaming + cache-delete on IOException,
                // so a CancellationException (pause/skip mid-open) would be
                // misread as cache corruption and nuke a valid entry. Let it
                // propagate so the open simply unwinds.
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                throw IOException("PCM cache index unreadable: ${indexFile.name}", t)
            }

            // Issue #1128 — integrity gate. A "complete" entry (its .idx.json
            // exists, so [PcmCache.isComplete] is true) can still be unusable
            // in ways that make the chapter SILENTLY SKIP rather than crash.
            // Each is rejected here as an IOException → EnginePlayer deletes
            // the entry and re-renders, instead of serving a broken chapter
            // forever (the #1128 manual-cache-clear bug):
            //
            //  - Degenerate index (zero sentences / non-positive totalBytes):
            //    written when a render's every sentence produced empty/declined
            //    PCM. This is the #1128 root cause; also guarded write-side in
            //    [`in`.jphe.storyvox.playback.cache.PcmAppender.complete]. Served
            //    as-is it yields an instant natural-end with no audio.
            //  - Manifest self-inconsistency (sentenceCount != sentences.size):
            //    a tell that the JSON was truncated mid-array yet still parsed.
            //  - Missing .pcm: the idx survived an eviction / chapter-sweep that
            //    failed to unlink its siblings ([PcmCache.delete]/[evictTo] swallow
            //    per-file errors), leaving idx-without-pcm.
            //  - Truncated .pcm: shorter than the index's declared totalBytes, or
            //    an index entry references bytes past EOF (disk-full mid-finalize,
            //    OS cache-dir reaper between finalize and open). Reading those
            //    ranges yields zero-length / garbage and drops the sentence.
            if (index.sentences.isEmpty() || index.totalBytes <= 0L) {
                throw IOException(
                    "PCM cache empty/degenerate: sentences=${index.sentences.size} " +
                        "totalBytes=${index.totalBytes}",
                )
            }
            if (index.sentenceCount != index.sentences.size) {
                throw IOException(
                    "PCM cache index inconsistent: sentenceCount=${index.sentenceCount} " +
                        "!= sentences.size=${index.sentences.size}",
                )
            }
            if (!pcmFile.isFile) {
                throw IOException("PCM cache file missing: ${pcmFile.name}")
            }
            // PR-D's appender writes exactly totalBytes; a smaller file
            // means truncation (disk-full mid-finalize, or wiped by
            // the OS cache-dir reaper between finalize and open).
            val pcmLen = pcmFile.length()
            if (pcmLen < index.totalBytes) {
                throw IOException(
                    "PCM cache file truncated: $pcmLen < ${index.totalBytes}",
                )
            }
            // No index entry may reference bytes past EOF — a corrupt entry
            // whose totalBytes looks fine but whose last offset is bogus would
            // read past the mmap / RandomAccessFile and drop that sentence.
            val maxExtent = index.sentences.maxOf { it.byteOffset + it.byteLen }
            if (maxExtent > pcmLen) {
                throw IOException(
                    "PCM cache index overruns file: maxExtent=$maxExtent > length=$pcmLen",
                )
            }
            val raf = RandomAccessFile(pcmFile, "r")
            val mapped: MappedByteBuffer? = runCatching {
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
            }.getOrNull()

            CacheFileSource(
                pcmFile = pcmFile,
                index = index,
                startSentenceIndex = startSentenceIndex,
                mapped = mapped,
                randomAccess = raf,
            )
        }
    }
}
