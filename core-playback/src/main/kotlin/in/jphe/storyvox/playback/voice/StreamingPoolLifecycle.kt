package `in`.jphe.storyvox.playback.voice

/**
 * epic/plugin-dx B2 — the pool-lifecycle tail of the voice swap
 * ([SwapStep], driven by [swapTo]) as a single, JVM-testable seam
 * (EnginePlayer itself can't be unit-instantiated). Encodes the
 * #1383/#1386-critical invariant: the stale pool is destroyed STRICTLY
 * BEFORE the new one is acquired (never double-resident), and destroy
 * failures are swallowed exactly like the inline
 * `runCatching { it.destroy() }` teardowns this replaces. Callers stop
 * the playback pipeline first (#89) and hold `engineMutex` on
 * `Dispatchers.IO` across the rebuild, exactly like the old loops.
 */
internal object StreamingPoolLifecycle {

    /** DESTROY_OWN_STALE_POOL then BUILD_SECONDARIES. Returns the new
     *  (possibly short — cap-on-failure — or empty) pool. */
    fun rebuild(
        old: List<StreamingSynth.Handle>,
        synth: StreamingSynth?,
        spec: ModelSpec,
        size: Int,
        threadsPerInstance: Int,
        tuning: StreamingTuning,
    ): List<StreamingSynth.Handle> {
        old.forEach { runCatching { it.destroy() } }
        if (synth == null || size <= 0) return emptyList()
        return synth.acquirePool(spec, size, threadsPerInstance, tuning)
    }

    /** Teardown-only (voice swap away from the pooled family, thermal-free
     *  release, engine shutdown). Returns the new empty pool value. */
    fun destroyAll(pool: List<StreamingSynth.Handle>): List<StreamingSynth.Handle> {
        pool.forEach { runCatching { it.destroy() } }
        return emptyList()
    }

    /** #1501 — true when the resident pool survives the swap's preamble:
     *  only a pooled target of the SAME family keeps it (to destroy it as
     *  its own stale pool right before the rebuild). Anything else — a
     *  different family, a non-pooled target, no pool — tears down. */
    fun keepsPoolThroughPreamble(currentFamily: String?, targetFamily: String, targetPools: Boolean): Boolean =
        targetPools && currentFamily == targetFamily

    /**
     * #1501 — the ONE voice-swap pool sequence EnginePlayer runs for every
     * target: a built-in pooled family, a de-sealed `StreamingSynth`
     * plugin, or a non-pooled engine ([synth] null). Replaces the per-arm
     * preamble + rebuild copies and makes the #1383/#1386 ordering (was the
     * specification-only `StreamingDispatch.swapStepOrder` data) production code:
     *
     *   [SwapStep.DESTROY_OTHER_FAMILY_POOLS] → [SwapStep.CONFIGURE_AND_LOAD_PRIMARY]
     *   → [SwapStep.DESTROY_OWN_STALE_POOL] → [SwapStep.BUILD_SECONDARIES]
     *
     * ([SwapStep.STOP_PIPELINE] is the caller's — `loadAndPlay` stops the
     * pipeline before taking engineMutex, #89.) A failed primary builds no
     * secondaries (the stale pool is still freed), so a failed load never
     * leaves a pool resident. [onStep] observes the order (tests).
     *
     * Call inside engineMutex on Dispatchers.IO, where the inline loops ran.
     */
    inline fun swapTo(
        currentPool: List<StreamingSynth.Handle>,
        currentFamily: String?,
        targetFamily: String,
        synth: StreamingSynth?,
        spec: ModelSpec,
        size: Int,
        threadsPerInstance: Int,
        tuning: StreamingTuning,
        onStep: (SwapStep) -> Unit,
        loadPrimary: () -> String,
    ): PoolSwap {
        var pool = currentPool
        if (!keepsPoolThroughPreamble(currentFamily, targetFamily, synth != null)) {
            onStep(SwapStep.DESTROY_OTHER_FAMILY_POOLS)
            pool = destroyAll(pool)
        }
        onStep(SwapStep.CONFIGURE_AND_LOAD_PRIMARY)
        val primary = loadPrimary()
        if (synth == null || primary != "Success") {
            if (pool.isNotEmpty()) {
                onStep(SwapStep.DESTROY_OWN_STALE_POOL)
                destroyAll(pool)
            }
            return PoolSwap(primary, emptyList(), null)
        }
        onStep(SwapStep.DESTROY_OWN_STALE_POOL)
        destroyAll(pool)
        onStep(SwapStep.BUILD_SECONDARIES)
        val fresh = rebuild(emptyList(), synth, spec, size, threadsPerInstance, tuning)
        return PoolSwap(primary, fresh, targetFamily)
    }
}

/** #1501 — outcome of [StreamingPoolLifecycle.swapTo]: the primary's load
 *  string, the new pool, and the family it is tagged with (null = none
 *  resident; the pipeline's family guard then runs serial). */
internal data class PoolSwap(
    val primaryResult: String,
    val pool: List<StreamingSynth.Handle>,
    val poolFamily: String?,
)

/** The voice-swap teardown/rebuild ordering — the #1383/#1386 regression
 *  minefield. [STOP_PIPELINE] comes FIRST (#89 — EngineStreamingSource.
 *  close's awaitTermination blocks until in-flight JNI generate() calls
 *  return, so every later destroy() runs on an idle instance);
 *  [DESTROY_OWN_STALE_POOL] strictly precedes [BUILD_SECONDARIES] (never
 *  double-resident). Executed by [StreamingPoolLifecycle.swapTo]. */
internal enum class SwapStep {
    STOP_PIPELINE,
    DESTROY_OTHER_FAMILY_POOLS,
    CONFIGURE_AND_LOAD_PRIMARY,
    DESTROY_OWN_STALE_POOL,
    BUILD_SECONDARIES,
}
