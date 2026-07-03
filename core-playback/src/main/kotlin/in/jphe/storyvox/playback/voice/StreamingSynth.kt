package `in`.jphe.storyvox.playback.voice

/**
 * Optional capability: engines that can run a pool of secondary native
 * instances for parallel chunk synthesis (epic/plugin-dx B2 — generalizes
 * the per-family `secondary*Engines` paths in `EnginePlayer`: Tier 3 #88
 * Piper/Kokoro, #119 Kitten).
 *
 * Consumption is NOT yet fully generic: `EnginePlayer` builds and adapts
 * pools inside per-family swap arms (hardcoded Piper/Kokoro/Kitten via
 * `byId(VoiceFamilyIds.*)`, plus the pooled-family handle branch), and
 * dispatch still discriminates on the sealed `EngineType`. A NEW pooled
 * engine therefore currently needs those EnginePlayer touchpoints in
 * addition to this interface — the registry `byKey` generic path exists
 * but is not yet the production route; completing that inversion is a
 * tracked plugin-dx follow-up. Pool sizing / governor decisions stay in
 * `StreamingDispatch`; this contract owns only the engine-specific
 * CONSTRUCTION, LOADING and DESTRUCTION of secondaries.
 * The sentence-distribution machinery is unchanged — handles are adapted
 * to `EngineStreamingSource.VoiceEngineHandle` at the EnginePlayer
 * boundary.
 *
 * ## Speaker is baked at construction — deliberately
 *
 * [Handle.generatePCM] takes NO `EngineKey`/speaker argument: secondary
 * instances are pinned to the active speaker at [acquirePool] time (it
 * rides in via [ModelSpec.OnnxTokensVoices.speakerId] /
 * [ModelSpec.SharedDir.speakerId], exactly like the inline loops they
 * replace). A per-call re-assert would imply mutable shared-speaker
 * semantics this pool intentionally does NOT have — that's why #1233
 * forces Kokoro serial when per-sentence language routing needs to
 * mutate speakers (see `StreamingDispatch.autoLangForcesSerial`).
 *
 * ## What is deliberately NOT a StreamingSynth
 *
 * Azure's lookahead fan-out reuses the same concurrency knob but hands
 * out synthetic handles over ONE shared HTTPS client — there is no
 * native instance lifecycle (nothing to construct, load or destroy), so
 * forcing it through this interface would be shape-faking. It stays
 * inline in `EnginePlayer`. Supertonic is serial by design (#1114 —
 * four ONNX graphs per session) and System TTS is serialized by the
 * framework (#676); neither implements this.
 *
 * ## Threading / lifecycle contract
 *
 * [acquirePool] performs native loads — call it where the old inline
 * loops ran: inside `engineMutex` on `Dispatchers.IO`. Callers own the
 * returned handles and MUST [Handle.destroy] each before dropping the
 * pool, observing `StreamingDispatch.swapStepOrder()`: pipeline stopped
 * first (#89 — destroys must hit idle instances), stale pool destroyed
 * strictly before a rebuild (#1383/#1386).
 */
interface StreamingSynth {

    /** One pooled secondary instance, model-loaded and speaker-pinned. */
    interface Handle {
        /** PCM sample rate this instance outputs (family-constant; sourced
         *  from the lock-free EngineSampleRateCache, #582). */
        val sampleRate: Int

        /** Synthesize one sentence to 16-bit mono LE PCM, or null on
         *  engine failure. Raw engine call — thread-priority policy
         *  (#801) is applied by the caller's adapter, not here. */
        fun generatePCM(text: String, speed: Float, pitch: Float): ByteArray?

        /** Destroy the native instance. Idempotence not required; callers
         *  invoke exactly once. Failures are swallowed (the old
         *  `runCatching { it.destroy() }` teardown contract). */
        fun destroy()
    }

    /**
     * Construct, configure and model-load up to [size] secondary
     * instances for [spec]. Returns the ACHIEVED pool — construction
     * caps at the first failed load (that instance is destroyed, the
     * prefix is kept: `StreamingDispatch.achievedSecondaries`). A
     * failed/short pool is not an error; playback runs with fewer
     * parallel producers, exactly like the inline loops it replaces.
     *
     * [threadsPerInstance] feeds the engine's loadModel `nt` parameter;
     * [tuning] carries the per-instance knobs the old loops propagated.
     */
    fun acquirePool(
        spec: ModelSpec,
        size: Int,
        threadsPerInstance: Int,
        tuning: StreamingTuning,
    ): List<Handle>
}

/**
 * Per-instance tuning the secondary-construction loops propagate
 * (epic/plugin-dx B2; deliberately MINIMAL — only what the inline loops
 * actually set today, no speculative config):
 * - [voiceSteady]: Piper selects its noiseScale/noiseScaleW pair from
 *   this (Steady vs Expressive; core-data NOISE_SCALE_* constants) so
 *   secondaries match the primary's prosody.
 * - [kokoroSilenceScale]: Kokoro's ABSOLUTE within-sentence silence
 *   scale — the caller pre-multiplies baseline × the user's
 *   punctuation-cadence multiplier (#196), same value it sets on the
 *   primary. Ignored by other families.
 */
data class StreamingTuning(
    val voiceSteady: Boolean,
    val kokoroSilenceScale: Float,
)
