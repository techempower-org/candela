package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.playback.tts.Sentence
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.encodeToString

/**
 * Streaming writer for one chapter's PCM cache entry.
 *
 * Returned by [PcmCache.appender]. The caller (PR-D's
 * `EngineStreamingSource` tee, or PR-F's `ChapterRenderJob`) calls
 * [appendSentence] for each generated sentence in order. On natural
 * end-of-chapter the caller calls [complete] to write the index sidecar
 * and mark the cache complete. On cancellation (user pause + don't
 * resume, voice swap, seek away) the caller calls [abandon] to delete
 * the partial files.
 *
 * Issue #581 — historically the completion method was named
 * `finalize()`, which silently shadowed [java.lang.Object.finalize]
 * (Kotlin doesn't require `override` for the deprecated GC hook).
 * When the GC reclaimed a leaked appender it re-invoked the same
 * method and the `check(!closed)` precondition threw on the
 * `FinalizerDaemon` thread. Renamed to [complete] so the GC sees a
 * plain Object.finalize no-op.
 *
 * **Not thread-safe.** The producer holds the only reference; concurrent
 * `appendSentence` calls would corrupt byte offsets in the in-memory
 * index.
 *
 * **No partial-finalize.** If the process is killed mid-render, the
 * `.pcm` and `.meta.json` survive but `.idx.json` doesn't — on next
 * boot the entry is "in progress" (PR-D recognizes this by `meta exists
 * + idx missing`). PR-D's resume policy will be "abandon, restart" since
 * the byte offsets we'd have written aren't on disk. PR-C provides the
 * mechanism (the missing-idx state); PR-D writes the policy.
 */
class PcmAppender internal constructor(
    private val pcmFile: File,
    private val metaFile: File,
    private val indexFile: File,
    private val sampleRate: Int,
    private val chapterId: String,
    private val voiceId: String,
    private val chunkerVersion: Int,
    private val speedHundredths: Int,
    private val pitchHundredths: Int,
) {
    /** Per-sentence byte offsets accumulated as we write. Index file is
     *  built from this at finalize time. */
    private val sentences = mutableListOf<PcmIndexEntry>()

    /** Running byte count == byte_offset for the next appended sentence. */
    private var totalBytesWritten: Long = 0L

    /** Open append-stream on the pcm file. Held for the appender's
     *  lifetime so we don't reopen + seek for each sentence. */
    private val pcmStream: FileOutputStream

    @Volatile private var closed: Boolean = false

    init {
        // Ensure parent dir exists (PcmCache.appender constructs the
        // entry's basename atomically; this is a defensive mkdir for
        // the case where someone wiped pcm-cache/ between construction
        // and first write).
        pcmFile.parentFile?.mkdirs()
        // Pre-existing pcm file means we're resuming an interrupted
        // render. Seek the running counter to the existing length so
        // subsequent appendSentence calls see correct byte_offsets.
        // PR-D's policy decides whether to actually resume vs abandon-
        // and-start-over; this just keeps us self-consistent if a
        // caller does choose to resume.
        totalBytesWritten = if (pcmFile.exists()) pcmFile.length() else 0L
        pcmStream = FileOutputStream(pcmFile, /* append = */ true)

        // Meta is written ONCE at construction. Stays untouched through
        // finalize (presence of idx.json is the completion signal, not
        // any meta mutation).
        if (!metaFile.exists()) {
            val meta = PcmMeta(
                chapterId = chapterId,
                voiceId = voiceId,
                sampleRate = sampleRate,
                createdEpochMs = System.currentTimeMillis(),
                chunkerVersion = chunkerVersion,
                speedHundredths = speedHundredths,
                pitchHundredths = pitchHundredths,
            )
            metaFile.writeText(pcmCacheJson.encodeToString(meta))
        }
    }

    /**
     * Append one sentence's PCM. Records its byte range in the in-memory
     * index. The bytes hit disk synchronously (FileOutputStream.write +
     * flush); we don't buffer because the producer's pace is so slow that
     * any extra latency is irrelevant, and we want the file to be a true
     * record of what's been generated for resume scenarios.
     *
     * Empty PCM (engine declined this sentence) is silently skipped:
     * no entry added to the index, no bytes written. Keeps the index
     * monotonic in `i` but allows gaps if the engine has a refusal mode.
     *
     * @throws IllegalStateException if the appender has been finalized
     *  or abandoned.
     */
    @Throws(IOException::class)
    fun appendSentence(sentence: Sentence, pcm: ByteArray, trailingSilenceMs: Int) {
        check(!closed) { "appender already closed (complete/abandon)" }
        if (pcm.isEmpty()) return

        val byteOffset = totalBytesWritten
        pcmStream.write(pcm)
        pcmStream.flush()
        totalBytesWritten += pcm.size

        sentences += PcmIndexEntry(
            i = sentence.index,
            start = sentence.startChar,
            end = sentence.endChar,
            byteOffset = byteOffset,
            byteLen = pcm.size,
            trailingSilenceMs = trailingSilenceMs,
        )
    }

    /**
     * Mark the cache entry complete: closes the pcm stream and writes
     * the `.idx.json` sidecar atomically (write to `.tmp`, rename).
     *
     * After complete the appender is closed; further calls throw.
     *
     * Issue #581 — this method used to be named `finalize()`, which
     * silently shadowed [java.lang.Object.finalize] (Kotlin doesn't
     * require the `override` keyword for the deprecated Object hook).
     * When the GC's `FinalizerDaemon` reclaimed a leaked
     * already-closed PcmAppender it invoked the same method and the
     * `check(!closed)` precondition threw on the finalizer thread,
     * producing the recurring `IllegalStateException: appender
     * already closed (finalize/abandon)` uncaught-exception spam
     * captured by the stress test (R5CRB0W66MK, monkey 5000-event
     * runs, ~5 occurrences per session). Renaming to [complete]
     * leaves a real `Object.finalize()` that the GC can call as a
     * no-op (default Object behavior) and a distinct user method that
     * only the explicit producer codepath invokes.
     */
    @Throws(IOException::class)
    fun complete() {
        check(!closed) { "appender already closed (complete/abandon)" }

        // Issue #1128 — refuse to finalize a degenerate (zero-sentence)
        // entry. A render whose every sentence produced empty/declined PCM
        // (transient engine failure, model decline, all-punctuation text)
        // would otherwise land a "complete" idx.json with sentenceCount=0 /
        // totalBytes=0. [PcmCache.isComplete] then returns true forever, the
        // cache-hit branch opens it, and [CacheFileSource.nextChunk] reports
        // an instant natural-end with zero audio — the chapter is SILENTLY
        // SKIPPED on every play, and the user's only recourse was the manual
        // "Clear cache" button (the #1128 report). Discard instead: the next
        // play is a clean cache MISS and re-renders, giving the engine
        // another chance rather than poisoning the cache. [CacheFileSource]
        // also rejects any such entry already on disk (read-side belt).
        if (sentences.isEmpty()) {
            abandon()
            return
        }

        closed = true
        runCatching { pcmStream.close() }

        val index = PcmIndex(
            sampleRate = sampleRate,
            sentenceCount = sentences.size,
            totalBytes = totalBytesWritten,
            sentences = sentences.toList(),
        )
        // Atomic write — write tmp then rename so a partial idx.json
        // never appears mid-write (a concurrent reader would mistake
        // it for "complete" when it's actually a half-flushed file).
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(pcmCacheJson.encodeToString(index))
        if (!tmp.renameTo(indexFile)) {
            // Fallback: rename can fail across some FS boundaries; copy
            // + delete is the universal escape hatch.
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Discard the in-progress entry: closes the stream, deletes the
     * pcm + meta + (any partial) idx files. After abandon the appender
     * is closed; further calls throw.
     */
    fun abandon() {
        if (closed) return
        closed = true
        runCatching { pcmStream.close() }
        runCatching { pcmFile.delete() }
        runCatching { metaFile.delete() }
        runCatching { indexFile.delete() }
        runCatching { File(indexFile.parentFile, indexFile.name + ".tmp").delete() }
    }
}
