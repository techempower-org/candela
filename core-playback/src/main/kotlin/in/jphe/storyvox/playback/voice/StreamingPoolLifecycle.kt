package `in`.jphe.storyvox.playback.voice

/**
 * epic/plugin-dx B2 — the pool-lifecycle tail of
 * `StreamingDispatch.swapStepOrder()` as a single, JVM-testable seam
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
}
