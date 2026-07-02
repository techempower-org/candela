package `in`.jphe.storyvox.playback.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * epic/plugin-dx B2 — fake-based proof of the StreamingSynth pool
 * lifecycle EnginePlayer drives through [StreamingPoolLifecycle]:
 * acquisition count, generate routing, and the #1383/#1386-critical
 * destroy-STALE-before-acquire ordering (`StreamingDispatch.swapStepOrder`).
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
            // before the first failing index (StreamingDispatch.achievedSecondaries).
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
}
