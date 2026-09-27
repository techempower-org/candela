package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.ThermalMonitor
import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds

/**
 * epic/plugin-dx B2 prep — the parallel-synth streaming DECISIONS from
 * [EnginePlayer], extracted as pure functions so they are unit-testable
 * (EnginePlayer itself can't be JVM-instantiated: Hilt + the VoxSherpa JNI
 * singletons). Behavior is pinned by `StreamingDispatchTest`.
 *
 * #1501 — every function here IS the production path. The former
 * specification-only helpers were wired or retired: which engines pool is
 * now the `StreamingSynth` capability itself (was a hardcoded
 * Piper/Kokoro/Kitten set); the swap preamble + teardown order execute in
 * `StreamingPoolLifecycle.swapTo` (was `preambleTeardownFamilies` /
 * `swapStepOrder` data); cap-on-failure is `buildCapOnFailurePool` (was the
 * duplicate `achievedSecondaries`).
 *
 * The decisions (not the I/O):
 *  - pool sizing off the user's parallel-synth slider (primary + N-1
 *    secondaries; the knob is [ParallelSynthConfig], NOT core count),
 *  - whether the resident pool may serve the active voice ([poolServes] —
 *    the family guard),
 *  - Azure's synthetic lookahead fan-out reusing the same knob,
 *  - the per-pipeline serial governors: #803 thermal (MODERATE+ drops the
 *    secondaries for this pipeline while the warm instances stay alive;
 *    #1126 applies it at construction, never mid-play) and #1233
 *    auto-language routing (Kokoro must run serial when per-sentence
 *    routing mutates the shared engine's speaker),
 *  - #803 SEVERE queue-depth halving.
 */
internal object StreamingDispatch {

    /** #1501 — the family guard: the resident pool (tagged [poolFamily] by
     *  `StreamingPoolLifecycle.swapTo`) may serve [active] only when it was
     *  built for the same engine. The pool can lag a cross-family voice
     *  swap (the deferred observeActiveVoice path and ensureVoiceLoaded
     *  update the active engine without rebuilding it, and the #569 fast
     *  path skips the swap entirely); handles from another family would
     *  voice routed sentences in the OLD voice, so a mismatch — or no
     *  active engine — runs serial. */
    fun poolServes(poolFamily: String?, active: EngineKey?): Boolean =
        poolFamily != null && active != null && poolFamily == active.engineId

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
}
