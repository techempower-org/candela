package `in`.jphe.storyvox.playback.voice

/** Stable, de-sealed engine discriminator (epic/plugin-dx). New engines get an id +
 *  optional speaker/params WITHOUT extending a sealed hierarchy.
 *
 *  #1500/#1501 — this is what catalog rows ([CatalogEntry], [UiVoiceInfo]) carry.
 *  The sealed [EngineType] is a typed VIEW of it: the six built-in families map
 *  to their variants, every other engine id to [EngineType.Plugin] — see
 *  [toEngineType]. */
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
    is EngineType.Plugin -> key
}

/** The six families that have a typed [EngineType] variant. Every other
 *  engine id is de-sealed and maps to [EngineType.Plugin]. */
internal val BUILT_IN_ENGINE_IDS: Set<String> = setOf(
    VoiceFamilyIds.PIPER,
    VoiceFamilyIds.KOKORO,
    VoiceFamilyIds.KITTEN,
    VoiceFamilyIds.SUPERTONIC,
    VoiceFamilyIds.AZURE,
    VoiceFamilyIds.SYSTEM_TTS,
)

/** The BUILT-IN typed variant for this key; `null` for de-sealed (non-built-in)
 *  ids or built-in keys missing the data their family requires (shared-model
 *  families need [EngineKey.speakerId]; Azure / SystemTts need their params) —
 *  never invents defaults. Use [toEngineType] for the total mapping. */
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

/** Total mapping: the built-in variant, or [EngineType.Plugin] for a de-sealed
 *  engine id. Throws only for a MALFORMED built-in key (e.g. a Kokoro key with
 *  no speakerId) — that is a programming error at the construction site. */
fun EngineKey.toEngineType(): EngineType =
    toEngineTypeOrNull() ?: run {
        require(engineId !in BUILT_IN_ENGINE_IDS) { "malformed built-in engine key $this" }
        EngineType.Plugin(this)
    }

/** [EngineKey.params] key names — the stable wire vocabulary. */
private const val PARAM_VOICE_NAME = "voiceName"
private const val PARAM_REGION = "region"
private const val PARAM_ENGINE_NAME = "engineName"
