package `in`.jphe.storyvox.playback.voice

import java.io.File

/** Data description of what a voice engine needs on disk to load (epic/plugin-dx).
 *  Replaces the per-engine `when` arms that built these paths inline
 *  (`AudiobookSynthesizer.loadModel` / `ChapterRenderJob.loadModel`). Built by
 *  [VoiceEnginePlugin.modelSpec], consumed by [VoiceEnginePlugin.loadModel]. */
sealed interface ModelSpec {
    /** Piper: per-voice onnx + tokens. */
    data class OnnxWithTokens(val onnx: File, val tokens: File) : ModelSpec

    /** Kokoro / Kitten: shared onnx + tokens + voices.bin. [speakerId] is the
     *  speaker index to activate before the native load — the old `when` arms
     *  called `setActiveSpeakerId` ahead of `loadModel`, so the spec carries it
     *  to keep that load-time assert byte-equivalent (synth-time re-assertion
     *  per #1263 lives in [VoiceEnginePlugin.generateAudioPCM] regardless). */
    data class OnnxTokensVoices(
        val onnx: File,
        val tokens: File,
        val voices: File,
        val speakerId: Int? = null,
    ) : ModelSpec

    /** Supertonic: one shared directory. [speakerId] as in [OnnxTokensVoices]. */
    data class SharedDir(val dir: File, val speakerId: Int? = null) : ModelSpec

    /** Cloud / framework engines: nothing to load locally. */
    data object None : ModelSpec

    /** #1500 — true when every file this spec names exists (the generic
     *  "is this voice playable" check for de-sealed engines; the built-ins
     *  keep their own shared-model checks in `VoiceManager`). */
    fun isPresentOnDisk(): Boolean = when (this) {
        is OnnxWithTokens -> onnx.exists() && tokens.exists()
        is OnnxTokensVoices -> onnx.exists() && tokens.exists() && voices.exists()
        is SharedDir -> dir.isDirectory && (dir.list()?.isNotEmpty() == true)
        None -> true
    }

    companion object {
        // On-disk voice-bundle artifact names — a filesystem contract shared
        // with the voice downloader. Moved from AudiobookSynthesizer's private
        // companion when the load arms moved into the plugins (epic/plugin-dx).
        internal const val MODEL_FILE = "model.onnx"
        internal const val TOKENS_FILE = "tokens.txt"
        internal const val VOICES_BIN_FILE = "voices.bin"

        /** Legacy vendor-engine contract: a null return from the native
         *  `loadModel` is reported as this error string. */
        internal const val ERR_LOAD_NULL = "Error: load returned null"
    }
}

/** #1500 — one file a de-sealed engine needs fetched before its voice is
 *  playable. `VoiceManager.download` fetches these generically, in order,
 *  for a voice whose engine has no built-in download arm. [sizeBytes] is an
 *  estimate for the progress bar (0 = unknown). */
data class ModelDownload(val url: String, val target: File, val sizeBytes: Long = 0L)
