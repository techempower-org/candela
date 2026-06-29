package `in`.jphe.storyvox.playback

import com.CodeBySonu.VoxSherpa.KittenEngine
import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.SupertonicEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine

/**
 * Issue #582 — ANR-grade lock-contention guard for the VoxSherpa
 * engine sample-rate accessor.
 *
 * The native engines (VoxSherpa's [VoiceEngine], [KokoroEngine],
 * [KittenEngine]) all use a single intrinsic monitor on the engine
 * instance to guard both `loadModel()` (~600-2000 ms on midrange
 * Android) and the property getters like `sampleRate`. When the main
 * thread reads `sampleRate` while a worker is partway through
 * `loadModel`, the JVM logs `Long monitor contention` and the UI is
 * stuck waiting for the lock — the stress test captured a 2.054 s
 * block on the Z Flip 3, well into ANR territory.
 *
 * Storyvox's reading flow only really needs a *fresh-enough* sample
 * rate after a model has been loaded once. The native engine's
 * sample rate is a property of the model, and within a process it
 * only ever changes when the user switches to a model with a
 * different native rate (Piper variants: 22.05k / 16k; Kokoro and
 * Kitten are both fixed at 24k by their architecture). So we can
 * cache the last-observed rate in a @Volatile field and let lock-
 * free readers pull from the cache.
 *
 * Three rules govern the cache:
 *
 *  1. **Lock-free readers** — [piperRate], [kokoroRate], [kittenRate]
 *     return the cached @Volatile field without touching the engine
 *     monitor. If we have a stored value, that's what we hand back —
 *     period. UI surfaces (progress meter, audio-out monitor,
 *     transport, etc.) never block on the load lock.
 *
 *  2. **First-read warmup** — if the cache is empty (process start,
 *     no model has ever been loaded), the reader DOES go to the
 *     engine. This is the safety valve: a callsite that legitimately
 *     needs the value before any prewarm has run won't see 0. The
 *     volatile is populated by that read so subsequent reads are
 *     lock-free again. The cost is bounded by whichever caller hits
 *     first — and the storyvox cold-launch sequence
 *     (`StoryvoxApp.warmDataLayer` → MainActivity `Choreographer`
 *     post-frame → VoiceEngine seeds via `seedVoiceEngineFromSettings`)
 *     intentionally schedules a prewarm read on IO well before the
 *     UI can request the rate, so the "first read on main" path is
 *     a theoretical-only branch under normal flow.
 *
 *  3. **Cache-population hook** — [refreshFromEngine] is called by
 *     the loader code AFTER `loadModel` returns. The post-load read
 *     is by definition off the contended window (the writer thread
 *     has released the monitor by the time `loadModel` returns), so
 *     it's safe; we sample once and write to the volatile.
 *
 * **Threading invariant**: the cache is whole-process; the three
 * engines are global singletons so a single cache slot per engine
 * is sufficient. Writers race on the volatile; whichever last-write
 * wins is the value readers see, which is the same semantics the
 * engine itself would have provided under the lock.
 *
 * **Why not @JvmStatic on the engine?** The fix really belongs in
 * VoxSherpa upstream — change `getSampleRate()` to read a
 * `@Volatile int` cached by `loadModel`. We don't have direct write
 * access to the vendor JNI surface; this storyvox-side shim achieves
 * the same outcome without forking the AAR. If/when we cut a
 * VoxSherpa update, we can collapse this to a thin pass-through.
 */
object EngineSampleRateCache {

    /** Default Piper sample rate when no model has ever been loaded.
     *  Most Piper voices ship at 22.05 kHz; the ones that don't get
     *  caught by the engine readback on first loadModel. Matches
     *  EnginePlayer's existing `DEFAULT_SAMPLE_RATE` fallback. */
    private const val DEFAULT_PIPER = 22050

    /** Default Kokoro sample rate. Architecturally fixed at 24 kHz
     *  across every Kokoro speaker shipped by sherpa-onnx, so this
     *  is effectively the engine's answer too. */
    private const val DEFAULT_KOKORO = 24000

    /** Default Kitten sample rate. Same architectural rate as Kokoro
     *  per #119 — 24 kHz across every speaker. */
    private const val DEFAULT_KITTEN = 24000

    /** Issue #1114 — Default Supertonic 3 sample rate (24 kHz, the
     *  standard for modern sherpa-onnx multi-speaker models). Used only as
     *  the pre-load fallback; once a model loads, [readSupertonicFromEngine]
     *  reads the engine's actual rate and seeds the cache. */
    private const val DEFAULT_SUPERTONIC = 24000

    /** Cached rates. 0 = "not yet observed"; readers fall through to
     *  the engine on the first read after process start. */
    @Volatile private var piperCached: Int = 0
    @Volatile private var kokoroCached: Int = 0
    @Volatile private var kittenCached: Int = 0
    @Volatile private var supertonicCached: Int = 0

    /** Lock-free reader for Piper. Returns the cached rate if known,
     *  otherwise hits the engine ONCE to seed the cache, otherwise
     *  falls back to the architectural default. Safe to call from
     *  any thread; on the main thread the cache hit is one volatile
     *  read with zero lock contention. */
    fun piperRate(): Int {
        val cached = piperCached
        if (cached > 0) return cached
        val fromEngine = readPiperFromEngine()
        if (fromEngine > 0) {
            piperCached = fromEngine
            return fromEngine
        }
        return DEFAULT_PIPER
    }

    /** Lock-free reader for Kokoro. See [piperRate] kdoc. */
    fun kokoroRate(): Int {
        val cached = kokoroCached
        if (cached > 0) return cached
        val fromEngine = readKokoroFromEngine()
        if (fromEngine > 0) {
            kokoroCached = fromEngine
            return fromEngine
        }
        return DEFAULT_KOKORO
    }

    /** Lock-free reader for Kitten. See [piperRate] kdoc. */
    fun kittenRate(): Int {
        val cached = kittenCached
        if (cached > 0) return cached
        val fromEngine = readKittenFromEngine()
        if (fromEngine > 0) {
            kittenCached = fromEngine
            return fromEngine
        }
        return DEFAULT_KITTEN
    }

    /** Issue #1114 — Lock-free reader for Supertonic 3. See [piperRate] kdoc.
     *  Reads the real rate from the loaded SupertonicEngine; the default is
     *  only the not-yet-loaded fallback. */
    fun supertonicRate(): Int {
        val cached = supertonicCached
        if (cached > 0) return cached
        val fromEngine = readSupertonicFromEngine()
        if (fromEngine > 0) {
            supertonicCached = fromEngine
            return fromEngine
        }
        return DEFAULT_SUPERTONIC
    }

    /** Post-loadModel hook: sample each engine off the contended
     *  window (the engine monitor is released by the time `loadModel`
     *  returns) and write to the volatile cache. Callers should
     *  invoke this right after a successful loadModel; safe to call
     *  redundantly or from any thread. Catches any throwable so a
     *  vendor JNI hiccup doesn't poison the playback pipeline. */
    fun refreshFromEngine() {
        runCatching {
            val p = readPiperFromEngine()
            if (p > 0) piperCached = p
        }
        runCatching {
            val k = readKokoroFromEngine()
            if (k > 0) kokoroCached = k
        }
        runCatching {
            val t = readKittenFromEngine()
            if (t > 0) kittenCached = t
        }
        runCatching {
            val s = readSupertonicFromEngine()
            if (s > 0) supertonicCached = s
        }
    }

    /** Test-only — clear the cache so a fresh read goes back to the
     *  engine. Visible for unit tests; not part of the public API. */
    internal fun clearForTest() {
        piperCached = 0
        kokoroCached = 0
        kittenCached = 0
        supertonicCached = 0
    }

    private fun readPiperFromEngine(): Int =
        runCatching { VoiceEngine.getInstance().sampleRate }.getOrDefault(0)

    private fun readKokoroFromEngine(): Int =
        runCatching { KokoroEngine.getInstance().sampleRate }.getOrDefault(0)

    private fun readKittenFromEngine(): Int =
        runCatching { KittenEngine.getInstance().sampleRate }.getOrDefault(0)

    /** Issue #1114 — reads the loaded Supertonic engine's sample rate.
     *  Returns 0 before a model is loaded so callers fall through to
     *  DEFAULT_SUPERTONIC (24 kHz). */
    private fun readSupertonicFromEngine(): Int =
        runCatching { SupertonicEngine.getInstance().sampleRate }.getOrDefault(0)
}
