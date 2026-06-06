package `in`.jphe.storyvox.playback.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.tts.Sentence
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filesystem-backed PCM cache. Owns `${context.cacheDir}/pcm-cache/`
 * and the `<sha>.pcm` / `<sha>.idx.json` / `<sha>.meta.json` triple
 * per cached chapter.
 *
 * The cache root lives in [Context.getCacheDir] (vs `filesDir`) on
 * purpose: Android may evict `cacheDir` under storage pressure even
 * without our help, and our LRU is conservative on top of that. A
 * surprise OS-level wipe is recoverable — next play re-renders. If
 * we'd put the cache in `filesDir` we'd be promising durability we
 * don't actually want.
 *
 * Concurrency:
 *  - Reads ([isComplete], [pcmFileFor], [indexFileFor], [totalSizeBytes]) are
 *    safe to call from any thread.
 *  - Writes ([evictTo], [delete]) are also thread-safe individually.
 *  - **At most one writer per key.** Issue #1034: the cross-boundary
 *    mutual exclusion the original kdoc promised ("PR-D enforces that
 *    mutual exclusion at the streaming-source / render-job boundary")
 *    was never actually implemented — the background [ChapterRenderJob]
 *    and the foreground streaming-tee could open two [PcmAppender]s on
 *    the SAME key and interleave append-mode writes (or one's [delete]
 *    could unlink files under the other's open stream), finalizing a
 *    corrupt entry. It is now enforced HERE: [openLease] hands out a
 *    single-owner [PcmAppenderLease] per [PcmCacheKey.fileBaseName].
 *    A second open for a live key is arbitrated — the FOREGROUND wins
 *    over a background WORKER, a WORKER is refused while the FOREGROUND
 *    owns the key (see [LeaseOwner]). The stale-partial delete + appender
 *    open happen together under the short [keyGuard] mutex, so a delete
 *    never races an open. The lock is NOT held across [appendSentence]
 *    or synthesis, so it can't deadlock with [EngineMutex] (which is
 *    taken per-sentence inside the producer loop).
 *
 * The raw [appender] entry point remains for tests and the M4B export
 * path that owns its key exclusively; callers that share keys across the
 * worker/foreground boundary MUST go through [openLease].
 */
@Singleton
class PcmCache(
    private val rootDir: File,
    private val config: PcmCacheConfig,
) {
    init {
        rootDir.mkdirs()
    }

    /**
     * Issue #1034 — guards [liveLeases] and the brief "delete stale +
     * open appender" / "evict + register" critical sections in [openLease]
     * and [releaseLease].
     *
     * A plain JVM monitor, NOT a coroutine [kotlinx.coroutines.sync.Mutex],
     * on purpose: every critical section is short, non-suspending file work
     * (a few `File.delete` syscalls + a `FileOutputStream` open), so a
     * monitor is correct and — crucially — lets both the suspend caller
     * ([ChapterRenderJob.doWork]) and the NON-suspend caller
     * ([`in`.jphe.storyvox.playback.tts.source.EngineStreamingSource.finalizeCache],
     * which runs on the consumer thread) drive a lease uniformly without
     * rippling `suspend` through the `PcmSource` interface. The lock is
     * NEVER held across [PcmAppender.appendSentence] or engine synthesis, so
     * it cannot deadlock with [EngineMutex] (taken per-sentence inside the
     * producer loop, after the lease is already open).
     */
    private val keyGuard = Any()

    /** basename ([PcmCacheKey.fileBaseName]) -> the live lease currently
     *  authorized to write that key. Guarded by [keyGuard]. */
    private val liveLeases = mutableMapOf<String, PcmAppenderLease>()

    /** Hilt entry point — anchors the cache root at
     *  `${context.cacheDir}/pcm-cache/`. The primary constructor takes a
     *  bare [File] so JVM unit tests can point at a temp folder without
     *  bootstrapping Robolectric. Same seam as [PcmCacheConfig]. */
    @Inject constructor(
        @ApplicationContext context: Context,
        config: PcmCacheConfig,
    ) : this(File(context.cacheDir, ROOT_DIR_NAME), config)

    /** Root directory exposed for tests + the future "Clear cache" UI. */
    fun rootDirectory(): File = rootDir

    fun pcmFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$PCM_SUFFIX")

    fun indexFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$INDEX_SUFFIX")

    fun metaFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$META_SUFFIX")

    /** True iff the index sidecar exists (i.e. a finalized render
     *  landed for this key). Cheap — single `File.exists` syscall. */
    fun isComplete(key: PcmCacheKey): Boolean = indexFileFor(key).exists()

    /**
     * Open a writer for [key]. If a previous render for the same key
     * was abandoned, leftover `.pcm` / `.meta.json` are NOT auto-deleted
     * here (the appender's `init` block treats existing pcm length as
     * the resume offset). PR-D decides resume vs restart; if it picks
     * restart, it should call [delete] first to wipe the partial entry.
     */
    fun appender(
        key: PcmCacheKey,
        sampleRate: Int,
    ): PcmAppender = PcmAppender(
        pcmFile = pcmFileFor(key),
        metaFile = metaFileFor(key),
        indexFile = indexFileFor(key),
        sampleRate = sampleRate,
        chapterId = key.chapterId,
        voiceId = key.voiceId,
        chunkerVersion = key.chunkerVersion,
        speedHundredths = key.speedHundredths,
        pitchHundredths = key.pitchHundredths,
    )

    /**
     * Who is asking for a write lease on a key. Drives [openLease]'s
     * arbitration when two callers contend for the same key (issue #1034).
     */
    enum class LeaseOwner {
        /** Live playback's streaming-tee. Always wins — playback must
         *  never be blocked behind, or corrupted by, a background render. */
        FOREGROUND,

        /** Background [ChapterRenderJob] pre-render. Yields to the
         *  foreground; refused while the foreground holds the key. */
        WORKER,
    }

    /**
     * Acquire the single writer lease for [key] (issue #1034).
     *
     * Atomically (under [keyGuard]): resolves contention with any
     * already-live lease, wipes a stale partial if we're starting fresh,
     * opens the [PcmAppender], and registers the new owner. Returns the
     * [PcmAppenderLease] to write through, or `null` if this caller is
     * not allowed to write the key right now.
     *
     * Arbitration:
     *  - Key free → granted to either owner.
     *  - Held by [LeaseOwner.WORKER], requested by [LeaseOwner.FOREGROUND]
     *    → the worker is **evicted** (its lease goes inactive and its
     *    open stream is closed + partial wiped) and the foreground is
     *    granted. Foreground always wins.
     *  - Held by [LeaseOwner.FOREGROUND], requested by [LeaseOwner.WORKER]
     *    → **refused** (`null`). The worker should skip — the foreground
     *    is already producing this exact key.
     *  - Held by the same owner type → refused (`null`). Defensive: the
     *    WorkManager `KEEP` policy and the single foreground pipeline make
     *    this unreachable in practice, but refusing keeps the invariant
     *    "one live writer per key" total.
     *
     * The stale-partial delete only runs when we open fresh (not when we
     * evict a worker mid-stream — there the evicted appender's [abandon]
     * already removes the partial). [keyGuard] is released before the
     * caller writes a single sentence, so synthesis + [EngineMutex] never
     * nest under it.
     */
    fun openLease(
        key: PcmCacheKey,
        sampleRate: Int,
        owner: LeaseOwner,
    ): PcmAppenderLease? = synchronized(keyGuard) {
        val basename = key.fileBaseName()
        val existing = liveLeases[basename]
        if (existing != null) {
            // Foreground wins over a worker; everything else is refused.
            if (!(owner == LeaseOwner.FOREGROUND && existing.owner == LeaseOwner.WORKER)) {
                return@synchronized null
            }
            // Evict the worker: deactivate the lease + close its stream and
            // wipe its partial so it can't leave half-written bytes behind.
            existing.deactivateAndAbandon()
            liveLeases.remove(basename)
        }

        // Fresh open: same abandon-and-restart policy as before — a leftover
        // partial (meta present, idx absent) is wiped before opening so the
        // appender starts at byte 0 rather than resuming onto stale bytes.
        if (metaFileFor(key).exists() && !isComplete(key)) {
            runCatching { pcmFileFor(key).delete() }
            runCatching { metaFileFor(key).delete() }
            runCatching { File(rootDir, "$basename$INDEX_SUFFIX$TMP_SUFFIX").delete() }
        }

        val lease = PcmAppenderLease(
            owner = owner,
            basename = basename,
            appender = appender(key, sampleRate),
            cache = this,
        )
        liveLeases[basename] = lease
        lease
    }

    /** Release [lease] from [liveLeases] if it's still the registered owner
     *  of its key. Called by the lease itself on complete/abandon. A lease
     *  that was already evicted (replaced in the map) is a no-op here. */
    internal fun releaseLease(lease: PcmAppenderLease) = synchronized(keyGuard) {
        if (liveLeases[lease.basename] === lease) {
            liveLeases.remove(lease.basename)
        }
    }

    /** Touch the pcm file's mtime — call on every successful play of
     *  a cached chapter so LRU eviction prefers genuinely-cold entries. */
    suspend fun touch(key: PcmCacheKey) = withContext(Dispatchers.IO) {
        pcmFileFor(key).setLastModified(System.currentTimeMillis())
        Unit
    }

    /** Delete pcm + idx + meta for [key]. Idempotent. */
    suspend fun delete(key: PcmCacheKey) = withContext(Dispatchers.IO) {
        val basename = key.fileBaseName()
        runCatching { pcmFileFor(key).delete() }
        runCatching { indexFileFor(key).delete() }
        runCatching { metaFileFor(key).delete() }
        runCatching { File(rootDir, "$basename$INDEX_SUFFIX$TMP_SUFFIX").delete() }
        Unit
    }

    /**
     * Delete every cached entry whose `.meta.json` references [chapterId].
     * Used when chapter text changes (re-imported, edited in source) —
     * the byte offsets in the index are wrong for the new text, so all
     * voice variants must go.
     */
    suspend fun deleteAllForChapter(chapterId: String) = withContext(Dispatchers.IO) {
        val metaFiles = rootDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }
            ?: return@withContext
        for (mf in metaFiles) {
            val basename = mf.name.removeSuffix(META_SUFFIX)
            val meta = runCatching {
                pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
            }.getOrNull() ?: continue
            if (meta.chapterId == chapterId) {
                runCatching { File(rootDir, "$basename$PCM_SUFFIX").delete() }
                runCatching { File(rootDir, "$basename$INDEX_SUFFIX").delete() }
                runCatching { mf.delete() }
            }
        }
    }

    /** Sum of `.pcm` file sizes under the cache root (idx + meta are
     *  trivially small; spec budget is dominated by audio). */
    suspend fun totalSizeBytes(): Long = withContext(Dispatchers.IO) {
        (rootDir.listFiles { f -> f.name.endsWith(PCM_SUFFIX) } ?: emptyArray())
            .sumOf { it.length() }
    }

    /**
     * LRU-evict (by .pcm mtime, oldest first) until total .pcm size ≤
     * [quotaBytes]. Entries whose key SHA matches [pinnedBasenames] are
     * skipped — caller passes basenames for currently-playing + next-
     * in-sequence chapters per spec.
     *
     * **Azure-survives-longer ordering (#186, PR-7).** Azure-rendered
     * entries cost real money to re-create; local engine entries are
     * free to re-render. The eviction order pins local entries to
     * earlier in the LRU list — non-Azure first (by mtime ascending),
     * then Azure (by mtime ascending). A 1980s Piper render gets
     * thrown away before a yesterday's Azure render. Azure detection
     * via the `.meta.json` file's voiceId — Azure ids start with
     * `azure_`. Meta-read failures default to non-Azure (we'd rather
     * over-evict than under-evict on a corrupt file; the cache
     * tolerates rebuilding any entry).
     *
     * Returns the number of entries evicted.
     */
    suspend fun evictTo(
        quotaBytes: Long,
        pinnedBasenames: Set<String> = emptySet(),
    ): Int = withContext(Dispatchers.IO) {
        var total = totalSizeBytes()
        if (total <= quotaBytes) return@withContext 0

        // Read each candidate's meta-file once to learn its voiceId.
        // The (isAzure, mtime) compound key sorts non-Azure-then-Azure,
        // mtime-ascending within each group. List sort is by file
        // mtime; the meta read amortizes across one eviction cycle.
        val candidates = (rootDir.listFiles { f -> f.name.endsWith(PCM_SUFFIX) } ?: emptyArray())
            .filter { it.name.removeSuffix(PCM_SUFFIX) !in pinnedBasenames }
            .map { pcm ->
                val basename = pcm.name.removeSuffix(PCM_SUFFIX)
                val isAzure = readMetaIsAzure(basename)
                Triple(pcm, isAzure, pcm.lastModified())
            }
            .sortedWith(compareBy({ it.second }, { it.third }))

        var evicted = 0
        for ((pcm, _, _) in candidates) {
            if (total <= quotaBytes) break
            val basename = pcm.name.removeSuffix(PCM_SUFFIX)
            val sz = pcm.length()
            runCatching { pcm.delete() }
            runCatching { File(rootDir, "$basename$INDEX_SUFFIX").delete() }
            runCatching { File(rootDir, "$basename$META_SUFFIX").delete() }
            total -= sz
            evicted++
        }
        evicted
    }

    /** PR-7 (#186) — true if the cache entry's meta-file declares an
     *  Azure voiceId. Azure voice ids ship with the `azure_` prefix
     *  per [VoiceCatalog.azureEntries]; future cloud providers should
     *  use a similarly distinguishable prefix if they want the same
     *  paid-renders-survive-longer treatment. */
    private fun readMetaIsAzure(basename: String): Boolean {
        val mf = File(rootDir, "$basename$META_SUFFIX")
        if (!mf.isFile) return false
        return runCatching {
            pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
                .voiceId.startsWith("azure_")
        }.getOrDefault(false)
    }

    /** Convenience overload — reads quota from [PcmCacheConfig]. */
    suspend fun evictToQuota(pinnedBasenames: Set<String> = emptySet()): Int =
        evictTo(config.quotaBytes(), pinnedBasenames)

    /** Wipe everything under the cache root. PR-G's "Clear cache" button. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        rootDir.listFiles()?.forEach { runCatching { it.delete() } }
        Unit
    }

    private companion object {
        const val ROOT_DIR_NAME = "pcm-cache"
        const val PCM_SUFFIX = ".pcm"
        const val INDEX_SUFFIX = ".idx.json"
        const val META_SUFFIX = ".meta.json"
        const val TMP_SUFFIX = ".tmp"
    }
}

/**
 * Single-owner write lease over one [PcmCacheKey]'s cache entry (issue
 * #1034). Obtained from [PcmCache.openLease]; wraps the underlying
 * [PcmAppender] and gates every mutation on still owning the key.
 *
 * The whole point: a lease that has been **evicted** (the foreground took
 * the key from a worker) flips [isActive] to `false`, so the loser's
 * remaining [appendSentence] / [complete] calls become no-ops instead of
 * corrupting the winner's entry. The loser never has to learn it lost —
 * it just stops being able to touch the file.
 *
 * Not itself thread-safe per instance (mirrors [PcmAppender]): one owner
 * holds the only reference and writes sentences in order. The cross-owner
 * safety lives in [PcmCache.openLease]'s [PcmCache.keyGuard] arbitration,
 * not here.
 */
class PcmAppenderLease internal constructor(
    val owner: PcmCache.LeaseOwner,
    internal val basename: String,
    private val appender: PcmAppender,
    private val cache: PcmCache,
) {
    @Volatile
    private var active: Boolean = true

    /** True while this lease still owns its key. Flips to `false` once the
     *  lease is completed, abandoned, or evicted by a higher-priority owner. */
    val isActive: Boolean get() = active

    /** Append one sentence's PCM — no-op if the lease is no longer active
     *  (evicted/closed). @see [PcmAppender.appendSentence]. */
    fun appendSentence(sentence: Sentence, pcm: ByteArray, trailingSilenceMs: Int) {
        if (!active) return
        appender.appendSentence(sentence, pcm, trailingSilenceMs)
    }

    /** Finalize the entry + release the key. No-op (releases nothing it
     *  doesn't own) if already inactive. @see [PcmAppender.complete]. */
    fun complete() {
        if (!active) return
        active = false
        appender.complete()
        cache.releaseLease(this)
    }

    /** Discard the partial entry + release the key. Idempotent.
     *  @see [PcmAppender.abandon]. */
    fun abandon() {
        if (!active) return
        active = false
        appender.abandon()
        cache.releaseLease(this)
    }

    /** Internal eviction path used by [PcmCache.openLease] when a
     *  higher-priority owner takes the key. Closes the underlying stream
     *  and wipes the partial so no half-written bytes survive, but does
     *  NOT call back into [PcmCache.releaseLease] — the caller already
     *  holds [PcmCache.keyGuard] and is swapping the map entry itself. */
    internal fun deactivateAndAbandon() {
        active = false
        appender.abandon()
    }
}
