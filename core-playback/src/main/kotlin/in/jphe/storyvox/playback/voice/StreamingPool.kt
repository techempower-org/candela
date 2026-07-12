package `in`.jphe.storyvox.playback.voice

/**
 * Issue #1503 — shared cap-on-failure Tier-3 pool builder, extracted from the
 * near-verbatim `acquirePool` in `PiperEnginePlugin` / `KokoroEnginePlugin` /
 * `KittenEnginePlugin` (#1488 ultrareview). The three implementations were
 * identical in cap-on-failure semantics; only the per-instance
 * "build + configure + load one engine" step and its Handle wrapper differed.
 * Those now live in the [attempt] lambda; this owns the shared loop, the cap
 * log, and the break.
 */
internal sealed interface PoolAttempt {
    /** The i-th secondary loaded; [handle] wraps the loaded native engine. */
    data class Loaded(val handle: StreamingSynth.Handle) : PoolAttempt

    /**
     * The i-th load failed with native [reason]. Per the destroy-on-failure
     * contract the caller ([attempt]) has ALREADY destroyed the failed
     * instance before returning this — [buildCapOnFailurePool] only logs + caps.
     */
    data class Failed(val reason: String) : PoolAttempt
}

/**
 * Issue #1503 — build a Tier-3 secondary-instance pool, capping at the first
 * failed load. Semantics preserved verbatim from the old inline `acquirePool`:
 * try to build up to [size] instances in order; on the FIRST [PoolAttempt.Failed]
 * (its instance already destroyed), log the cap (family + reason + the count
 * we're capping at) and STOP, returning the instances built so far. All-success
 * returns [size] handles; [size] <= 0 returns empty.
 *
 * The only side effect — the cap warning — goes through [warn] (default
 * `android.util.Log.w`) so the cap-on-failure logic is JVM-unit-testable with a
 * fake logger, without native engines or a Robolectric sandbox. That is
 * coverage the inline `acquirePool` never had (`acquirePool` is "out of JVM
 * scope" per the voice contract kit).
 */
internal fun buildCapOnFailurePool(
    size: Int,
    family: String,
    logTag: String,
    warn: (tag: String, msg: String) -> Unit = { tag, msg -> android.util.Log.w(tag, msg) },
    attempt: (index: Int) -> PoolAttempt,
): List<StreamingSynth.Handle> {
    val handles = mutableListOf<StreamingSynth.Handle>()
    for (i in 1..size) {
        when (val result = attempt(i)) {
            is PoolAttempt.Loaded -> handles += result.handle
            is PoolAttempt.Failed -> {
                warn(
                    logTag,
                    "Tier 3 secondary $i ($family) load failed: ${result.reason} — " +
                        "capping at ${handles.size + 1} instances.",
                )
                break
            }
        }
    }
    return handles
}
