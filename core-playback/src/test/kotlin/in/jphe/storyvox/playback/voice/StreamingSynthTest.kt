package `in`.jphe.storyvox.playback.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * epic/plugin-dx B2 — fake-based proof of the StreamingSynth pool
 * lifecycle EnginePlayer drives through [StreamingPoolLifecycle]:
 * acquisition count, generate routing, and the #1383/#1386-critical
 * destroy-STALE-before-acquire ordering, and (#1501) the full voice-swap
 * sequence [StreamingPoolLifecycle.swapTo] runs for every engine.
 */
class StreamingSynthTest {

    /** Shared event log so cross-object ordering is assertable. */
    private val events = mutableListOf<String>()

    private inner class FakeHandle(private val id: String) : StreamingSynth.Handle {
        override val sampleRate: Int = 24_000
        override fun generatePCM(text: String, speed: Float, pitch: Float): ByteArray? {
            events += "generate:$id:$text"
            return byteArrayOf(1)
        }
        override fun destroy() {
            events += "destroy:$id"
        }
    }

    private inner class FakeStreamingSynth(
        private val poolPrefix: String,
        private val failFromIndex: Int = Int.MAX_VALUE,
    ) : StreamingSynth {
        val acquireCalls = mutableListOf<Triple<ModelSpec, Int, StreamingTuning>>()
        override fun acquirePool(
            spec: ModelSpec,
            size: Int,
            threadsPerInstance: Int,
            tuning: StreamingTuning,
        ): List<StreamingSynth.Handle> {
            events += "acquire:$poolPrefix:size=$size:nt=$threadsPerInstance"
            acquireCalls += Triple(spec, size, tuning)
            // Cap-on-failure semantics: the achieved pool is the prefix
            // before the first failing index (buildCapOnFailurePool).
            val achieved = minOf(size, failFromIndex)
            return List(achieved) { FakeHandle("$poolPrefix-${it + 1}") }
        }
    }

    private val spec = ModelSpec.OnnxTokensVoices(
        File("/k/model.onnx"),
        File("/k/tokens.txt"),
        File("/k/voices.bin"),
        speakerId = 3,
    )
    private val tuning = StreamingTuning(voiceSteady = true, kokoroSilenceScale = 0.2f)

    @Test fun `rebuild acquires the requested pool size and routes generate calls`() {
        val synth = FakeStreamingSynth("kokoro")
        val pool = StreamingPoolLifecycle.rebuild(
            old = emptyList(),
            synth = synth,
            spec = spec,
            size = 3,
            threadsPerInstance = 2,
            tuning = tuning,
        )
        assertEquals(3, pool.size)
        assertEquals(1, synth.acquireCalls.size)
        assertEquals(3, synth.acquireCalls[0].second)
        assertEquals(tuning, synth.acquireCalls[0].third)

        pool[1].generatePCM("hello", 1f, 1f)
        assertTrue("generate routed to the second handle", "generate:kokoro-2:hello" in events)
    }

    @Test fun `rebuild destroys every stale handle BEFORE acquiring the new pool`() {
        val synth = FakeStreamingSynth("new")
        val stale = listOf<StreamingSynth.Handle>(FakeHandle("old-1"), FakeHandle("old-2"))

        val pool = StreamingPoolLifecycle.rebuild(
            old = stale,
            synth = synth,
            spec = spec,
            size = 2,
            threadsPerInstance = 0,
            tuning = tuning,
        )

        assertEquals(2, pool.size)
        val destroyOld1 = events.indexOf("destroy:old-1")
        val destroyOld2 = events.indexOf("destroy:old-2")
        val acquire = events.indexOfFirst { it.startsWith("acquire:new") }
        assertTrue("old-1 destroyed", destroyOld1 >= 0)
        assertTrue("old-2 destroyed", destroyOld2 >= 0)
        assertTrue("acquire happened", acquire >= 0)
        // The #1383/#1386 invariant: DESTROY_OWN_STALE_POOL strictly
        // precedes BUILD_SECONDARIES — never double-resident.
        assertTrue("destroys precede acquire", destroyOld1 < acquire && destroyOld2 < acquire)
    }

    @Test fun `rebuild with no synth or zero size only destroys`() {
        val stale = listOf<StreamingSynth.Handle>(FakeHandle("old-1"))
        val emptyPool = StreamingPoolLifecycle.rebuild(
            old = stale,
            synth = null,
            spec = ModelSpec.None,
            size = 3,
            threadsPerInstance = 0,
            tuning = tuning,
        )
        assertEquals(0, emptyPool.size)
        assertTrue("destroy:old-1" in events)

        val synth = FakeStreamingSynth("unused")
        val serial = StreamingPoolLifecycle.rebuild(
            old = emptyList(),
            synth = synth,
            spec = spec,
            size = 0,
            threadsPerInstance = 0,
            tuning = tuning,
        )
        assertEquals(0, serial.size)
        assertEquals("size=0 never reaches acquirePool", 0, synth.acquireCalls.size)
    }

    @Test fun `short pool from cap-on-failure is used as-is`() {
        val synth = FakeStreamingSynth("piper", failFromIndex = 1)
        val pool = StreamingPoolLifecycle.rebuild(
            old = emptyList(),
            synth = synth,
            spec = spec,
            size = 7,
            threadsPerInstance = 4,
            tuning = tuning,
        )
        assertEquals(1, pool.size)
    }

    @Test fun `destroyAll destroys every handle and returns the empty pool`() {
        val pool = listOf<StreamingSynth.Handle>(FakeHandle("a"), FakeHandle("b"))
        val after = StreamingPoolLifecycle.destroyAll(pool)
        assertEquals(0, after.size)
        assertTrue("destroy:a" in events)
        assertTrue("destroy:b" in events)
    }

    // ---- #1501: StreamingPoolLifecycle.swapTo — the production swap path ----

    private val steps = mutableListOf<SwapStep>()

    private fun swap(
        currentPool: List<StreamingSynth.Handle>,
        currentFamily: String?,
        targetFamily: String,
        synth: StreamingSynth?,
        primary: String = "Success",
        size: Int = 2,
    ): PoolSwap = StreamingPoolLifecycle.swapTo(
        currentPool = currentPool,
        currentFamily = currentFamily,
        targetFamily = targetFamily,
        synth = synth,
        spec = spec,
        size = size,
        threadsPerInstance = 1,
        tuning = tuning,
        onStep = { steps += it },
    ) {
        events += "primary:$targetFamily"
        primary
    }

    @Test fun `swap to a pooled engine runs the pinned order`() {
        val stale = listOf<StreamingSynth.Handle>(FakeHandle("k-old"))
        val result = swap(stale, "voice_kokoro", "voice_kokoro", FakeStreamingSynth("k"))
        // Same family: the pool survives the preamble and is destroyed as
        // the own stale pool, strictly before the rebuild (#1383/#1386).
        assertEquals(
            listOf(
                SwapStep.CONFIGURE_AND_LOAD_PRIMARY,
                SwapStep.DESTROY_OWN_STALE_POOL,
                SwapStep.BUILD_SECONDARIES,
            ),
            steps,
        )
        val primary = events.indexOf("primary:voice_kokoro")
        val destroy = events.indexOf("destroy:k-old")
        val acquire = events.indexOfFirst { it.startsWith("acquire:k") }
        assertTrue(primary < destroy && destroy < acquire)
        assertEquals(2, result.pool.size)
        assertEquals("voice_kokoro", result.poolFamily)
        assertEquals("Success", result.primaryResult)
    }

    @Test fun `swap across families frees the other pool before the primary loads`() {
        val piperPool = listOf<StreamingSynth.Handle>(FakeHandle("p-1"))
        val result = swap(piperPool, "voice_piper", "voice_kitten", FakeStreamingSynth("kit"))
        assertEquals(SwapStep.DESTROY_OTHER_FAMILY_POOLS, steps.first())
        assertTrue(events.indexOf("destroy:p-1") < events.indexOf("primary:voice_kitten"))
        assertEquals("voice_kitten", result.poolFamily)
        assertEquals(2, result.pool.size)
    }

    @Test fun `swap to a non-pooled engine tears down and runs serial`() {
        val pool = listOf<StreamingSynth.Handle>(FakeHandle("p-1"))
        val result = swap(pool, "voice_piper", "voice_supertonic", synth = null)
        assertEquals(
            listOf(SwapStep.DESTROY_OTHER_FAMILY_POOLS, SwapStep.CONFIGURE_AND_LOAD_PRIMARY),
            steps,
        )
        assertTrue("destroy:p-1" in events)
        assertTrue(result.pool.isEmpty())
        assertEquals(null, result.poolFamily)
    }

    @Test fun `a failed primary leaves no pool resident`() {
        val stale = listOf<StreamingSynth.Handle>(FakeHandle("k-old"))
        val synth = FakeStreamingSynth("k")
        val result = swap(stale, "voice_kokoro", "voice_kokoro", synth, primary = "Error: boom")
        assertEquals("Error: boom", result.primaryResult)
        assertTrue("destroy:k-old" in events)
        assertTrue("no secondaries built", synth.acquireCalls.isEmpty())
        assertTrue(result.pool.isEmpty())
        assertEquals(null, result.poolFamily)
    }

    @Test fun `slider at one tags the family with an empty pool`() {
        val result = swap(emptyList(), null, "voice_piper", FakeStreamingSynth("p"), size = 0)
        assertTrue(result.pool.isEmpty())
        assertEquals("voice_piper", result.poolFamily)
    }

    @Test fun `preamble keeps only a same-family pooled target's pool`() {
        assertTrue(StreamingPoolLifecycle.keepsPoolThroughPreamble("voice_kokoro", "voice_kokoro", true))
        assertFalse(StreamingPoolLifecycle.keepsPoolThroughPreamble("voice_piper", "voice_kokoro", true))
        assertFalse(StreamingPoolLifecycle.keepsPoolThroughPreamble("voice_kokoro", "voice_kokoro", false))
        assertFalse(StreamingPoolLifecycle.keepsPoolThroughPreamble(null, "voice_kokoro", true))
    }
}
