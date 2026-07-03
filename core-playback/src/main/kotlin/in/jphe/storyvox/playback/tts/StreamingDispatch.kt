package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.ThermalMonitor
import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds

/**
 * epic/plugin-dx B2 prep — the parallel-synth streaming DECISIONS from
 * [EnginePlayer], extracted verbatim as pure functions so they are unit-
 * testable (EnginePlayer itself can't be JVM-instantiated: Hilt + the
 * VoxSherpa JNI singletons). Behavior is pinned by `StreamingDispatchTest`.
 * Wiring status is MIXED and matters when editing either side:
 * [desiredSecondaryCount], [azureLookaheadCount], [thermalForcesSerial],
 * [autoLangForcesSerial] and [queueDepth] ARE the production path —
 * EnginePlayer calls them. [buildsNativePool], [preambleTeardownFamilies],
 * [achievedSecondaries] and [swapStepOrder] are SPECIFICATION-ONLY:
 * EnginePlayer still inlines their equivalents per swap arm (the
 * `streamingPoolFamily != VoiceFamilyIds.X` preambles and per-family pool
 * builds), so their tests pin the intended invariants, not the running
 * code. Keep the inline sites and these functions in step until the
 * dispatch inversion (plugin-dx follow-up) makes them the single path.
 *
 * The decisions (not the I/O):
 *  - which engine families run a native secondary pool (Tier 3 #88, Kitten
 *    #119; Supertonic stays serial — 4-graph sessions are memory-heavy;
 *    System TTS is serialized by the framework),
 *  - pool sizing off the user's parallel-synth slider (primary + N-1
 *    secondaries; the knob is [ParallelSynthConfig], NOT core count),
 *  - Azure's synthetic lookahead fan-out reusing the same knob,
 *  - the per-pipeline serial governors: #803 thermal (MODERATE+ drops the
 *    secondaries for this pipeline while the warm instances stay alive;
 *    #1126 applies it at construction, never mid-play) and #1233
 *    auto-language routing (Kokoro must run serial when per-sentence
 *    routing mutates the shared engine's speaker),
 *  - #803 SEVERE queue-depth halving,
 *  - which families' pools are torn down in a voice-swap arm's preamble
 *    (all pooled families except the target's own — the target's stale
 *    pool is destroyed just before its rebuild).
 */
internal object StreamingDispatch {

    /** Engine families that build a pool of native secondary instances
     *  (Tier 3 #88 Piper/Kokoro; #119 Kitten). */
    val NATIVE_POOL_FAMILIES: Set<String> = setOf(
        VoiceFamilyIds.PIPER,
        VoiceFamilyIds.KOKORO,
        VoiceFamilyIds.KITTEN,
    )

    /** True when [key]'s family builds native secondary instances. */
    fun buildsNativePool(key: EngineKey): Boolean = key.engineId in NATIVE_POOL_FAMILIES

    /** Secondary-pool size for the user's configured [instances] count:
     *  primary singleton + N-1 secondaries (loop `1 until instances`). */
    fun desiredSecondaryCount(instances: Int): Int = (instances - 1).coerceAtLeast(0)

    /** Azure lookahead handle count — the same slider fans out N-1
     *  synthetic handles over the shared HTTPS client (no native
     *  lifecycle); see the Azure branch of the handle dispatch. */
    fun azureLookaheadCount(instances: Int): Int = (instances - 1).coerceAtLeast(0)

    /** #803 — MODERATE+ thermal pressure runs this pipeline serial
     *  (drops the secondaries for the pipeline lifetime; the warm
     *  instances stay alive for the next cool pipeline). */
    fun thermalForcesSerial(thermalStatus: Int, poolNonEmpty: Boolean): Boolean =
        thermalStatus >= ThermalMonitor.THERMAL_STATUS_MODERATE && poolNonEmpty

    /** #1233 — per-sentence language routing mutates the shared Kokoro
     *  singleton's active speaker; only the serial producer serializes
     *  that safely. Parallel secondaries are pinned to the primary
     *  speaker at load and would voice routed sentences wrongly. */
    fun autoLangForcesSerial(
        autoLanguageDetection: Boolean,
        key: EngineKey,
        poolNonEmpty: Boolean,
    ): Boolean =
        autoLanguageDetection && key.engineId == VoiceFamilyIds.KOKORO && poolNonEmpty

    /** #803 — SEVERE+ halves the producer queue depth, floored at 2 so
     *  the pipeline doesn't starve. */
    fun queueDepth(base: Int, thermalStatus: Int): Int =
        if (thermalStatus >= ThermalMonitor.THERMAL_STATUS_SEVERE) {
            (base / 2).coerceAtLeast(2)
        } else {
            base
        }

    /** Families whose pools a voice-swap arm destroys in its PREAMBLE
     *  when loading [target]: every pooled family except the target's
     *  own (a pooled target destroys its own STALE pool just before the
     *  rebuild; non-pooled targets — Supertonic/Azure/SystemTts — free
     *  all three). */
    fun preambleTeardownFamilies(target: EngineKey): Set<String> =
        NATIVE_POOL_FAMILIES - setOfNotNull(target.engineId.takeIf { it in NATIVE_POOL_FAMILIES })

    /** Cap-on-failure policy for secondary construction: the pool is the
     *  prefix of successful loads — the first failed secondary is
     *  destroyed and construction STOPS ("capping at k+1 instances"),
     *  it does not skip-and-continue. */
    fun achievedSecondaries(loadResults: List<Boolean>): Int =
        loadResults.takeWhile { it }.count()

    /** The voice-swap teardown/rebuild ordering, pinned as data — this is
     *  the #1383/#1386 regression minefield. Invariants it encodes:
     *  [SwapStep.STOP_PIPELINE] comes FIRST (#89 — EngineStreamingSource.
     *  close's awaitTermination blocks until in-flight JNI generate()
     *  calls return, so every later destroy() runs on an idle instance);
     *  [SwapStep.DESTROY_OWN_STALE_POOL] strictly precedes
     *  [SwapStep.BUILD_SECONDARIES] (never double-resident). */
    enum class SwapStep {
        STOP_PIPELINE,
        DESTROY_OTHER_FAMILY_POOLS,
        CONFIGURE_AND_LOAD_PRIMARY,
        DESTROY_OWN_STALE_POOL,
        BUILD_SECONDARIES,
    }

    /** See [SwapStep]. */
    fun swapStepOrder(): List<SwapStep> = listOf(
        SwapStep.STOP_PIPELINE,
        SwapStep.DESTROY_OTHER_FAMILY_POOLS,
        SwapStep.CONFIGURE_AND_LOAD_PRIMARY,
        SwapStep.DESTROY_OWN_STALE_POOL,
        SwapStep.BUILD_SECONDARIES,
    )
}
