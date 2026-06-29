package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1383 — pure-function unit test for [shouldRebuildForVoiceChange], the
 * gate that decides whether a [VoiceManager.activeVoice] emission represents a
 * genuine user voice change worth a pipeline rebuild, or a spurious re-emission
 * to ignore.
 *
 * The bug: on a Samsung Z Flip3, Piper produced only intermittent fragments and
 * usually no audio at all. Logcat showed the streaming producer cancelled ~35 ms
 * after each `pcm-cache MISS`, then another MISS, in a loop:
 *
 * ```
 * pcm-cache MISS chapter=3609400 voice=piper_cori_en_GB_medium fromSentence=11
 * serial producer: cancelled (close/seek/voice swap) — silent exit
 * pcm-cache MISS chapter=3609400 voice=piper_cori_en_GB_medium fromSentence=11
 * ```
 *
 * Root cause: `activeVoice` is `combine(prefs, azureRoster, systemTtsRoster)`,
 * so it re-emits on ANY input change. The framework `TextToSpeech` client the
 * system-TTS roster is built from connect/disconnect-loops on Samsung (#1384),
 * churning `systemTtsRoster` and re-firing `activeVoice` with the SAME active
 * id many times a second. [EnginePlayer.observeActiveVoice] reacted to each with
 * `stopPlaybackPipeline()` (which closes the in-flight EngineStreamingSource and
 * cancels its producer) + `loadAndPlay()` — so the churn cancelled Piper
 * synthesis before it could emit a single chunk.
 *
 * The pre-fix `newId == loadedVoiceId` guard couldn't stop it: `loadedVoiceId`
 * only updates AFTER a full model load, so churn arriving while the first load
 * is still in flight slips past it. The fix adds [lastReactedVoiceId] — the id
 * of the last emission we acted on — as a churn-dedup key that is updated the
 * instant we decide to react, closing that window.
 *
 * Like [EnginePlayerAutoPlayDecisionTest] / [EnginePlayerThermalRebuildTest]
 * this deliberately stays at the pure-function layer; a full EnginePlayer needs
 * a Hilt graph + sherpa-onnx AARs and a real AudioTrack.
 */
class EnginePlayerVoiceChurnTest {

    private val piper = "piper_cori_en_GB_medium"
    private val kokoro = "kokoro_af_heart"
    private val system = "system_com.google.android.tts"

    @Test
    fun `a genuine change of the selected voice rebuilds`() {
        // First-ever pick (nothing loaded, nothing reacted to yet).
        assertTrue(shouldRebuildForVoiceChange(piper, lastReactedVoiceId = null, loadedVoiceId = null))
        // Real swap from a loaded voice to a different one.
        assertTrue(shouldRebuildForVoiceChange(kokoro, lastReactedVoiceId = piper, loadedVoiceId = piper))
    }

    @Test
    fun `a same-id re-emission is dropped even before the model finishes loading`() {
        // The #1383 window: we already reacted to `piper` and kicked off a
        // load, but loadedVoiceId is still the OLD voice (or null) because the
        // model load hasn't returned. A churn re-emission of `piper` must NOT
        // rebuild — that's what cancels synthesis mid-MISS.
        assertFalse(shouldRebuildForVoiceChange(piper, lastReactedVoiceId = piper, loadedVoiceId = null))
        assertFalse(shouldRebuildForVoiceChange(piper, lastReactedVoiceId = piper, loadedVoiceId = kokoro))
    }

    @Test
    fun `re-selecting the already-loaded voice does not rebuild`() {
        assertFalse(shouldRebuildForVoiceChange(piper, lastReactedVoiceId = piper, loadedVoiceId = piper))
        // Even if we never recorded a reaction, a match against the loaded
        // voice (e.g. loadAndPlay loaded it directly) is a no-op.
        assertFalse(shouldRebuildForVoiceChange(piper, lastReactedVoiceId = null, loadedVoiceId = piper))
    }

    @Test
    fun `a null active voice never rebuilds`() {
        assertFalse(shouldRebuildForVoiceChange(null, lastReactedVoiceId = piper, loadedVoiceId = piper))
        assertFalse(shouldRebuildForVoiceChange(null, lastReactedVoiceId = null, loadedVoiceId = null))
    }

    @Test
    fun `system-TTS voice swaps are unaffected`() {
        // The fix only suppresses SAME-id re-emissions; switching to or from a
        // system-TTS voice is a real id change and still rebuilds.
        assertTrue(shouldRebuildForVoiceChange(system, lastReactedVoiceId = piper, loadedVoiceId = piper))
        assertTrue(shouldRebuildForVoiceChange(piper, lastReactedVoiceId = system, loadedVoiceId = system))
    }

    /**
     * Drives the exact [EnginePlayer.observeActiveVoice] gate over a simulated
     * emission stream and counts how many pipeline rebuilds it would trigger.
     * Mirrors the collector: track [lastReactedVoiceId] and react only when
     * [shouldRebuildForVoiceChange] returns true.
     *
     * `loadedVoiceId` is held FIXED at [initialLoaded] for the whole stream —
     * the #1383 window. The load never completes because every churn-driven
     * rebuild tears it down before `loadedVoiceId` can advance, so the
     * pre-existing `newId == loadedVoiceId` guard provides no help here. Only
     * [lastReactedVoiceId] can suppress the burst; if this test passes with the
     * `lastReactedVoiceId` guard removed, the fix has regressed.
     */
    private fun countRebuilds(emissions: List<String?>, initialLoaded: String? = null): Int {
        var lastReacted: String? = null
        val loaded = initialLoaded
        var rebuilds = 0
        for (id in emissions) {
            if (id == null) continue
            if (id == loaded) { lastReacted = id; continue }
            if (!shouldRebuildForVoiceChange(id, lastReacted, loaded)) continue
            lastReacted = id
            rebuilds++
        }
        return rebuilds
    }

    @Test
    fun `a roster-churn burst of the same voice produces exactly one rebuild`() {
        // #1383 reproduction: one real pick of Piper, then the system-TTS
        // roster churns and re-fires the same id 200 times while the model load
        // is still in flight (loadedVoiceId never advances). Pre-fix this was
        // 200 teardown+rebuilds (synthesis never survived). Post-fix: 1.
        val churn = listOf(piper) + List(200) { piper }
        assertEquals(
            "roster churn must not rebuild the pipeline more than once for one voice (#1383)",
            1,
            countRebuilds(churn),
        )
    }

    @Test
    fun `churn interleaved with a genuine swap still honors the swap exactly once each`() {
        // Piper (real) → churn → churn → Kokoro (real) → churn → back to Piper
        // (real, because it differs from the last-reacted Kokoro). Three real
        // changes; the churn in between suppressed — even though loadedVoiceId
        // never advances across the whole stream.
        val stream = listOf(
            piper, piper, piper,
            kokoro, kokoro,
            piper, piper,
        )
        assertEquals(3, countRebuilds(stream))
    }

    @Test
    fun `the loaded voice re-emitting does not rebuild even amid churn`() {
        // Player already loaded Piper via a direct loadAndPlay (loaded = piper
        // from the start). A churn storm of the loaded voice stays a no-op via
        // the pre-existing loadedVoiceId guard.
        assertEquals(0, countRebuilds(List(50) { piper }, initialLoaded = piper))
    }
}
