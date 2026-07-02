package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineKeyTest {
    @Test fun `round-trips every sealed EngineType`() {
        val cases = listOf(
            EngineType.Piper,
            EngineType.Kokoro(3),
            EngineType.Kitten(1),
            EngineType.Supertonic(2),
            EngineType.Azure("en-US-AvaDragonHDLatestNeural", "westus"),
            // Real SystemTts shape (UiVoiceInfo.kt): engine package + voice id.
            EngineType.SystemTts("com.google.android.tts", "en-us-x-iol-network"),
        )
        for (t in cases) assertEquals(t, t.toEngineKey().toEngineType())
    }

    @Test fun `unknown engineId maps to null not crash`() {
        assertEquals(null, EngineKey("voice_martian", 0).toEngineTypeOrNull())
    }

    @Test fun `shared-model key without a speaker is not a valid EngineType`() {
        // Kokoro/Kitten/Supertonic need a speaker index; a bare family key
        // must map to null rather than invent speaker 0.
        assertEquals(null, EngineKey(VoiceFamilyIds.KOKORO).toEngineTypeOrNull())
        assertEquals(null, EngineKey(VoiceFamilyIds.KITTEN).toEngineTypeOrNull())
        assertEquals(null, EngineKey(VoiceFamilyIds.SUPERTONIC).toEngineTypeOrNull())
    }

    @Test fun `param-carrying keys with missing params are not valid EngineTypes`() {
        assertEquals(null, EngineKey(VoiceFamilyIds.AZURE).toEngineTypeOrNull())
        assertEquals(
            null,
            EngineKey(
                VoiceFamilyIds.SYSTEM_TTS,
                params = mapOf("engineName" to "com.google.android.tts"),
            ).toEngineTypeOrNull(),
        )
    }
}
