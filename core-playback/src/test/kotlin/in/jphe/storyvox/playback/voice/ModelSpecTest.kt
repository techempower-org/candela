package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ModelSpecTest {
    @Test fun `piper spec carries onnx and tokens`() {
        val spec = ModelSpec.OnnxWithTokens(File("/v/model.onnx"), File("/v/tokens.txt"))
        assertEquals("/v/model.onnx", spec.onnx.path)
    }

    @Test fun `none spec for engines without local models`() {
        assertEquals(ModelSpec.None, ModelSpec.None)
    }

    @Test fun `shared-model specs carry the speaker to activate`() {
        val kokoro = ModelSpec.OnnxTokensVoices(
            File("/k/model.onnx"),
            File("/k/tokens.txt"),
            File("/k/voices.bin"),
            speakerId = 3,
        )
        assertEquals(3, kokoro.speakerId)
        assertEquals("/k/voices.bin", kokoro.voices.path)

        val supertonic = ModelSpec.SharedDir(File("/s"), speakerId = 2)
        assertEquals(2, supertonic.speakerId)
    }
}
