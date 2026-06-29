package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #543 — voice-aware "Warming Brian…" status subtitle.
 *
 * Pure-logic tests for [playerStatusSubtitle] + [warmingMessageForVoice].
 * The composable can't render in a JVM unit test (no Android renderer),
 * but the wording the user sees during the warmup window is the
 * critical behavior — pinning it here prevents a future refactor from
 * silently reverting to the generic "Voice waking up…" string.
 *
 * Issue #537 (UI side) — the subtitle never showed the literal word
 * "idle" (it only renders chapter title / warming / buffering branches),
 * so the on-screen surface doesn't carry the bug. The DebugOverlay's
 * `pipelineStateText` is where #537 lives; see [DebugOverlayStateLabelTest].
 */
class AudiobookViewTextTest {

    @Test
    fun `subtitle shows loading copy when chapter title is blank`() {
        assertEquals(
            "Loading voice + chapter text",
            playerStatusSubtitle(
                chapterTitle = "",
                warmingUp = false,
                buffering = false,
                voiceLabel = "",
            ),
        )
    }

    @Test
    fun `subtitle shows just the chapter title in steady state`() {
        assertEquals(
            "Chapter 4 — The Bonewright",
            playerStatusSubtitle(
                chapterTitle = "Chapter 4 — The Bonewright",
                warmingUp = false,
                buffering = false,
                voiceLabel = "azure:en-US-BrianNeural",
            ),
        )
    }

    @Test
    fun `subtitle appends Buffering when buffering`() {
        assertEquals(
            "Chapter 4 · Buffering…",
            playerStatusSubtitle(
                chapterTitle = "Chapter 4",
                warmingUp = false,
                buffering = true,
                voiceLabel = "azure:en-US-BrianNeural",
            ),
        )
    }

    /**
     * #543 acceptance criterion: when state == Warming AND voice is
     * Brian, the listener sees "Warming Brian…" — not the generic
     * "Voice waking up…". This test pins the most-cited example from
     * the spec.
     */
    @Test
    fun `warming with Azure Brian shows Warming Brian`() {
        assertEquals(
            "Chapter 4 · Warming Brian…",
            playerStatusSubtitle(
                chapterTitle = "Chapter 4",
                warmingUp = true,
                buffering = false,
                voiceLabel = "azure:en-US-BrianNeural",
            ),
        )
    }

    /**
     * Issue #1319 — when the engine publishes a render-ready
     * [`in`.jphe.storyvox.playback.EngineState.Warming] message, the subtitle
     * prefers it over the voiceLabel-derived fallback. The voiceLabel here is
     * Brian but the upstream says "Aurelia", so the upstream must win.
     */
    @Test
    fun `warming prefers the upstream engine message over the derived label`() {
        assertEquals(
            "Chapter 4 · Warming Aurelia…",
            playerStatusSubtitle(
                chapterTitle = "Chapter 4",
                warmingUp = true,
                buffering = false,
                voiceLabel = "azure:en-US-BrianNeural",
                warmingMessage = "Warming Aurelia…",
            ),
        )
    }

    @Test
    fun `warming falls back to derived label when upstream message is blank`() {
        assertEquals(
            "Chapter 4 · Warming Brian…",
            playerStatusSubtitle(
                chapterTitle = "Chapter 4",
                warmingUp = true,
                buffering = false,
                voiceLabel = "azure:en-US-BrianNeural",
                warmingMessage = "   ",
            ),
        )
    }

    @Test
    fun `warming with Azure Ava strips Multilingual suffix`() {
        assertEquals(
            "Warming Ava…",
            warmingMessageForVoice("azure:en-US-AvaMultilingualNeural"),
        )
    }

    @Test
    fun `warming with Piper amy capitalizes`() {
        assertEquals(
            "Warming Amy…",
            warmingMessageForVoice("piper:en_US-amy-medium"),
        )
    }

    @Test
    fun `warming with Piper lessac capitalizes`() {
        assertEquals(
            "Warming Lessac…",
            warmingMessageForVoice("piper:en_US-lessac-low"),
        )
    }

    @Test
    fun `warming with VoxSherpa descriptive voice falls back to generic`() {
        // VoxSherpa's `tier3/narrator-warm` doesn't have a real
        // speaker name — "narrator-warm" is a descriptor. Surfacing
        // "Warming Narrator…" would read as robotic; the generic
        // copy is the right answer here.
        assertEquals(
            "Warming voice…",
            warmingMessageForVoice("voxsherpa:tier3/narrator-warm"),
        )
    }

    @Test
    fun `warming with blank voice label falls back to generic`() {
        assertEquals("Warming voice…", warmingMessageForVoice(""))
    }

    @Test
    fun `warming with bare voice id (no engine prefix) also extracts name`() {
        // Cold-launch path: the voice label arrives before the engine
        // prefix is resolved. Still extract the speaker name from the
        // BCP-47 portion.
        assertEquals(
            "Warming Ryan…",
            warmingMessageForVoice("en-GB-RyanNeural"),
        )
    }
}
