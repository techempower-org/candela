package `in`.jphe.storyvox.playback.voice

/** Stable, de-sealed engine discriminator (epic/plugin-dx). New engines get an id +
 *  optional speaker/params WITHOUT extending a sealed hierarchy. The sealed
 *  [EngineType] remains as a compatibility shim until B3 removes external references. */
data class EngineKey(
    val engineId: String,                         // a VoiceFamilyIds constant
    val speakerId: Int? = null,
    val params: Map<String, String> = emptyMap(), // Azure: voiceName/region; SystemTts: engineName/voiceName
)

fun EngineType.toEngineKey(): EngineKey = when (this) {
    is EngineType.Piper -> EngineKey(VoiceFamilyIds.PIPER)
    is EngineType.Kokoro -> EngineKey(VoiceFamilyIds.KOKORO, speakerId)
    is EngineType.Kitten -> EngineKey(VoiceFamilyIds.KITTEN, speakerId)
    is EngineType.Supertonic -> EngineKey(VoiceFamilyIds.SUPERTONIC, speakerId)
    is EngineType.Azure -> EngineKey(
        VoiceFamilyIds.AZURE,
        null,
        mapOf(PARAM_VOICE_NAME to voiceName, PARAM_REGION to region),
    )
    is EngineType.SystemTts -> EngineKey(
        VoiceFamilyIds.SYSTEM_TTS,
        null,
        mapOf(PARAM_ENGINE_NAME to engineName, PARAM_VOICE_NAME to voiceName),
    )
}

/** Inverse mapping; `null` for unknown ids or keys missing the data their
 *  family requires (shared-model families need [EngineKey.speakerId];
 *  Azure / SystemTts need their params) — never invents defaults. */
fun EngineKey.toEngineTypeOrNull(): EngineType? = when (engineId) {
    VoiceFamilyIds.PIPER -> EngineType.Piper
    VoiceFamilyIds.KOKORO -> speakerId?.let { EngineType.Kokoro(it) }
    VoiceFamilyIds.KITTEN -> speakerId?.let { EngineType.Kitten(it) }
    VoiceFamilyIds.SUPERTONIC -> speakerId?.let { EngineType.Supertonic(it) }
    VoiceFamilyIds.AZURE -> {
        val voiceName = params[PARAM_VOICE_NAME]
        val region = params[PARAM_REGION]
        if (voiceName != null && region != null) EngineType.Azure(voiceName, region) else null
    }
    VoiceFamilyIds.SYSTEM_TTS -> {
        val engineName = params[PARAM_ENGINE_NAME]
        val voiceName = params[PARAM_VOICE_NAME]
        if (engineName != null && voiceName != null) EngineType.SystemTts(engineName, voiceName) else null
    }
    else -> null
}

fun EngineKey.toEngineType(): EngineType =
    requireNotNull(toEngineTypeOrNull()) { "unknown engine $engineId" }

/** [EngineKey.params] key names — the stable wire vocabulary. */
private const val PARAM_VOICE_NAME = "voiceName"
private const val PARAM_REGION = "region"
private const val PARAM_ENGINE_NAME = "engineName"
