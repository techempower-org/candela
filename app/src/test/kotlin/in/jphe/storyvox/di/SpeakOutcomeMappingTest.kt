package `in`.jphe.storyvox.di

import `in`.jphe.storyvox.feature.api.UiSpeakOutcome
import `in`.jphe.storyvox.playback.SpeakOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #1776 — core-playback read-aloud outcomes map onto the feature contract. */
class SpeakOutcomeMappingTest {

    @Test
    fun `every playback outcome maps to a UI outcome`() {
        val expected = mapOf(
            SpeakOutcome.Started to UiSpeakOutcome.Started,
            SpeakOutcome.NoVoice to UiSpeakOutcome.NoVoice,
            SpeakOutcome.EngineUnavailable to UiSpeakOutcome.Unavailable,
            SpeakOutcome.Superseded to UiSpeakOutcome.Cancelled,
            SpeakOutcome.Blank to UiSpeakOutcome.Cancelled,
        )
        assertEquals(SpeakOutcome.entries.toSet(), expected.keys)
        expected.forEach { (core, ui) -> assertEquals(ui, core.toUiSpeakOutcome()) }
    }
}
