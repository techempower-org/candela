package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    // ── #1595 — engine-state merge preserves controller-owned fields ──

    @Test fun `withEngineUpdate preserves the user's shake-to-extend OFF choice`() {
        // Regression for the #1595 clobber: the engine never sets
        // shakeToExtendEnabled, so its copy carries the data-class default
        // (true). A naive engine.copy(...) reset the user's OFF back to ON
        // on every ~50ms poll. withEngineUpdate must keep the controller's
        // value from `prev`.
        val prev = PlaybackState(shakeToExtendEnabled = false, isPlaying = false)
        val engine = PlaybackState(shakeToExtendEnabled = true, isPlaying = true)

        val merged = prev.withEngineUpdate(engine, sleepTimerRemainingMs = null)

        assertFalse("user OFF must survive the engine merge", merged.shakeToExtendEnabled)
    }

    @Test fun `withEngineUpdate keeps ON when the user has it enabled`() {
        val prev = PlaybackState(shakeToExtendEnabled = true)
        val engine = PlaybackState(shakeToExtendEnabled = true)
        assertTrue(prev.withEngineUpdate(engine, sleepTimerRemainingMs = null).shakeToExtendEnabled)
    }

    @Test fun `withEngineUpdate takes engine-owned playback fields from the engine copy`() {
        val prev = PlaybackState(isPlaying = false, isBuffering = false, voiceId = "old")
        val engine = PlaybackState(isPlaying = true, isBuffering = true, voiceId = "new")

        val merged = prev.withEngineUpdate(engine, sleepTimerRemainingMs = 5_000L)

        assertTrue(merged.isPlaying)
        assertTrue(merged.isBuffering)
        assertEquals("new", merged.voiceId)
    }

    @Test fun `withEngineUpdate uses the explicitly-passed sleep timer value, not the engine default`() {
        val prev = PlaybackState(sleepTimerRemainingMs = 123L)
        val engine = PlaybackState(sleepTimerRemainingMs = null) // engine's stale default
        assertEquals(9_000L, prev.withEngineUpdate(engine, sleepTimerRemainingMs = 9_000L).sleepTimerRemainingMs)
    }

    @Test fun `scrubProgress is zero when duration is zero`() {
        assertEquals(0f, PlaybackState().scrubProgress(), 0f)
    }

    @Test fun `scrubProgress is zero when duration is negative`() {
        val s = PlaybackState(durationEstimateMs = -100L, charOffset = 50)
        assertEquals(0f, s.scrubProgress(), 0f)
    }

    @Test fun `scrubProgress is zero at start of chapter`() {
        val s = PlaybackState(durationEstimateMs = 10_000L, charOffset = 0)
        assertEquals(0f, s.scrubProgress(), 0f)
    }

    @Test fun `scrubProgress at half-way charOffset is roughly half`() {
        // #555 — duration + position both live on the speed-invariant
        // media-time axis. 1000ms / 12.5 chars/s = 12.5 chars total
        // regardless of speed (SPEED_BASELINE_WPM 150 * 5 / 60 = 12.5
        // chars/sec.)
        val total = (1_000f / 1000f) * SPEED_BASELINE_CHARS_PER_SECOND
        val s = PlaybackState(
            durationEstimateMs = 1_000L,
            charOffset = (total / 2f).toInt(),
            speed = 1.0f,
        )
        assertEquals(0.5f, s.scrubProgress(), 0.05f)
    }

    @Test fun `scrubProgress is clamped to 1 when charOffset overshoots`() {
        val s = PlaybackState(
            durationEstimateMs = 1_000L,
            charOffset = 10_000,
            speed = 1.0f,
        )
        assertEquals(1f, s.scrubProgress(), 0f)
    }

    @Test fun `scrubProgress is speed-invariant — same charOffset, same fraction`() {
        // #555 — both the rail and the position now live on the speed-1
        // axis. A given charOffset / duration pair yields the same
        // progress fraction regardless of speed (which is the whole
        // point — speed changes shouldn't shift the visual scrubber).
        val base = PlaybackState(durationEstimateMs = 10_000L, charOffset = 100, speed = 1.0f)
        val fast = base.copy(speed = 2.0f)
        assertEquals(base.scrubProgress(), fast.scrubProgress(), 1e-4f)
    }

    @Test fun `speed baseline constants are stable contract`() {
        assertEquals(150f, SPEED_BASELINE_WPM, 0f)
        assertEquals(12.5f, SPEED_BASELINE_CHARS_PER_SECOND, 0f)
    }

    /**
     * Issue #561 (stuck-state-fixer) — when a chapter loads, the engine
     * MUST surface the active voice id into [PlaybackState.voiceId]. The
     * debug overlay's "name" / "voice" / "tier" rows feed off this field
     * (via RealDebugRepositoryUi.displayEngineName), and a null
     * voiceId paints them all as "—" — the audit's "blank fields"
     * symptom. This test pins the contract: a copy with `voiceId="..."`
     * is the post-load shape, and `displayEngineName` should resolve
     * to a non-empty label.
     */
    @Test fun `voiceId round-trips and resolves engine name`() {
        val s = PlaybackState(voiceId = "piper:lessac")
        // Round-trip via Serializable to make sure the field survives
        // background save/restore (Service kill, process death).
        val json = kotlinx.serialization.json.Json.encodeToString(PlaybackState.serializer(), s)
        val round = kotlinx.serialization.json.Json.decodeFromString(PlaybackState.serializer(), json)
        assertEquals("piper:lessac", round.voiceId)
        // The display heuristic in RealDebugRepositoryUi keys on the
        // colon prefix. "piper:lessac" -> "VoxSherpa · Piper".
        val derived = when {
            s.voiceId?.startsWith("azure:") == true -> "Azure"
            s.voiceId?.startsWith("kokoro:") == true -> "VoxSherpa · Kokoro"
            s.voiceId?.startsWith("piper:") == true -> "VoxSherpa · Piper"
            else -> "—"
        }
        assertEquals("VoxSherpa · Piper", derived)
    }

    @Test fun `isBuffering defaults false and serializes round-trip`() {
        val s = PlaybackState(isBuffering = true, isPlaying = true, charOffset = 42)
        val json = kotlinx.serialization.json.Json.encodeToString(PlaybackState.serializer(), s)
        val round = kotlinx.serialization.json.Json.decodeFromString(PlaybackState.serializer(), json)
        assertEquals(true, round.isBuffering)
        assertEquals(true, round.isPlaying)
        assertEquals(42, round.charOffset)

        val default = PlaybackState()
        assertEquals(false, default.isBuffering)
    }
}
