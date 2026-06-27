package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1192 — pins the off-main-thread safety contract of
 * [resolveLiveAudioPositionMs], the resolver behind [EnginePlayer.currentPositionMs]'s
 * live-audio (radio) branch.
 *
 * The crash: `audioStreamPlayer` is a Media3 ExoPlayer thread-confined to the
 * Main looper, but currentPositionMs() is documented "safe to call from any
 * thread" and is polled off-main every 50 ms by PlaybackController's position
 * loop (scope = Dispatchers.Default). The pre-fix radio branch read
 * `audioStreamPlayer.currentPosition` directly, so the first off-main poll
 * after a station loaded threw
 * `IllegalStateException: Player is accessed on the wrong thread` — the same
 * wrong-thread class already fixed at #969 / #553 for other engine touches.
 *
 * The headline guarantee is [`off-main never samples the thread-confined player`]:
 * the player-sampling lambda must NOT run off the main looper.
 *
 * Stays at the pure-function layer — full EnginePlayer construction needs a
 * Hilt graph + sherpa-onnx AARs (same constraint documented on
 * EnginePlayerPositionCheckpointTest).
 */
class EnginePlayerLiveAudioPositionTest {

    @Test
    fun `off-main never samples the thread-confined player`() {
        // The #1192 crash guard: PlaybackController's 50 ms poll runs on
        // Dispatchers.Default (no main looper). The sampling lambda would read
        // ExoPlayer.currentPosition and throw — it must not be invoked.
        var sampled = false
        val r = resolveLiveAudioPositionMs(
            onMainLooper = false,
            cachedMs = 1_234L,
            lastTruthfulMs = 1_000L,
            samplePlayerPositionMs = { sampled = true; 9_999L },
        )
        assertFalse(
            "off-main callers must NOT touch the Main-confined ExoPlayer (#1192)",
            sampled,
        )
        // Off-main serves the last main-sampled cache value (latched).
        assertEquals(1_234L, r.cachedMs)
        assertEquals(1_234L, r.reportedMs)
    }

    @Test
    fun `on-main samples the player and refreshes the cache`() {
        val r = resolveLiveAudioPositionMs(
            onMainLooper = true,
            cachedMs = 0L,
            lastTruthfulMs = 0L,
            samplePlayerPositionMs = { 4_200L },
        )
        assertEquals("on-main refreshes the cache from the live player", 4_200L, r.cachedMs)
        assertEquals(4_200L, r.reportedMs)
    }

    @Test
    fun `position never regresses within a chapter`() {
        // #536 monotonic latch — a fresh sample that dips (e.g. ExoPlayer
        // re-clamps during a buffering blip) must not drag the reported
        // position backwards, even though the raw cache tracks the dip.
        val r = resolveLiveAudioPositionMs(
            onMainLooper = true,
            cachedMs = 5_000L,
            lastTruthfulMs = 5_000L,
            samplePlayerPositionMs = { 100L },
        )
        assertEquals("reported position stays latched at the high-water mark", 5_000L, r.reportedMs)
        assertEquals("cache still tracks the raw sample for the next read", 100L, r.cachedMs)
    }

    @Test
    fun `null player position on-main falls back to the cache`() {
        // Window between isLiveAudioChapter=true and ensureAudioStreamPlayer():
        // the player isn't built yet, so the sample is null. Reuse the cache
        // rather than collapsing to 0 (avoids a transient scrubber glitch, #536).
        val r = resolveLiveAudioPositionMs(
            onMainLooper = true,
            cachedMs = 777L,
            lastTruthfulMs = 0L,
            samplePlayerPositionMs = { null },
        )
        assertEquals(777L, r.cachedMs)
        assertEquals(777L, r.reportedMs)
    }

    @Test
    fun `on-main advances the cache as the live stream plays`() {
        // Successive main-thread samples advance both the cache and the latch.
        var raw = 1_000L
        val first = resolveLiveAudioPositionMs(
            onMainLooper = true,
            cachedMs = 0L,
            lastTruthfulMs = 0L,
            samplePlayerPositionMs = { raw },
        )
        raw = 2_500L
        val second = resolveLiveAudioPositionMs(
            onMainLooper = true,
            cachedMs = first.cachedMs,
            lastTruthfulMs = first.reportedMs,
            samplePlayerPositionMs = { raw },
        )
        assertTrue("live position advances", second.reportedMs > first.reportedMs)
        assertEquals(2_500L, second.reportedMs)
    }
}
