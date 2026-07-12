package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1503 — cap-on-failure semantics of the shared [buildCapOnFailurePool],
 * extracted from the Piper/Kokoro/Kitten `acquirePool` triplicate. Runs as a
 * plain JVM test: the per-instance native load is faked via the `attempt`
 * lambda and the only Android side effect (the cap `Log.w`) is captured through
 * the injectable `warn`, so this executes in CI (no native engines, no
 * Robolectric sandbox / JDK-21 requirement). The inline `acquirePool` was "out
 * of JVM scope" per the voice contract kit — this is coverage it never had.
 */
class StreamingPoolTest {

    private val warnings = mutableListOf<String>()
    private val warn: (String, String) -> Unit = { _, msg -> warnings += msg }

    private fun fakeHandle(): StreamingSynth.Handle = object : StreamingSynth.Handle {
        override val sampleRate: Int = 24_000
        override fun generatePCM(text: String, speed: Float, pitch: Float): ByteArray? = null
        override fun destroy() {}
    }

    @Test
    fun `all loads succeed builds the full pool and never warns`() {
        val pool = buildCapOnFailurePool(3, "Piper", "TAG", warn) {
            PoolAttempt.Loaded(fakeHandle())
        }
        assertEquals(3, pool.size)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `first failure caps and stops, keeping the instances built so far`() {
        var lastAttempt = 0
        val pool = buildCapOnFailurePool(5, "Kokoro", "TAG", warn) { i ->
            lastAttempt = i
            if (i <= 2) PoolAttempt.Loaded(fakeHandle()) else PoolAttempt.Failed("native boom")
        }
        assertEquals(2, pool.size)        // two successes kept
        assertEquals(3, lastAttempt)      // stopped at the first failure (attempt 3), no attempt 4/5
        assertEquals(
            listOf("Tier 3 secondary 3 (Kokoro) load failed: native boom — capping at 3 instances."),
            warnings,
        )
    }

    @Test
    fun `immediate failure returns empty and caps at one`() {
        val pool = buildCapOnFailurePool(4, "Kitten", "TAG", warn) {
            PoolAttempt.Failed("no model")
        }
        assertEquals(0, pool.size)
        assertEquals(
            listOf("Tier 3 secondary 1 (Kitten) load failed: no model — capping at 1 instances."),
            warnings,
        )
    }

    @Test
    fun `size zero or negative builds nothing`() {
        assertEquals(0, buildCapOnFailurePool(0, "Piper", "TAG", warn) { PoolAttempt.Loaded(fakeHandle()) }.size)
        assertEquals(0, buildCapOnFailurePool(-1, "Piper", "TAG", warn) { PoolAttempt.Loaded(fakeHandle()) }.size)
        assertEquals(emptyList<String>(), warnings)
    }
}
