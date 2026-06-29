package `in`.jphe.storyvox.playback.voice

/**
 * Issue #1372 — the canonical [VoiceFamilyDescriptor] literals, lifted
 * out of [VoiceFamilyRegistry] so they have a single home.
 *
 * Both [VoiceFamilyRegistry.descriptors] (the curated, ordered list the
 * Plugin Manager renders) and each `VoiceEnginePlugin.familyDescriptor()`
 * reference these constants, so the family metadata can't drift between
 * the two surfaces. Extracting them is behaviour-preserving: the
 * registry list is rebuilt from exactly these objects in the same order.
 *
 * The `displayName` / `description` / `sizeHint` copy is unchanged from
 * the pre-#1372 inline literals — `VoiceFamilyRegistryTest` pins the id
 * set, count, and placeholder behaviour.
 */
object VoiceFamilyDescriptors {

    /** Issue #676 — Android System TTS, the zero-download first-launch tier. */
    val SYSTEM_TTS = VoiceFamilyDescriptor(
        id = VoiceFamilyIds.SYSTEM_TTS,
        displayName = "System TTS",
        description = "Uses your device's built-in voice — no download needed",
        sourceUrl = "https://developer.android.com/reference/android/speech/tts/TextToSpeech",
        license = "Bundled with Android — varies by engine (Google / Samsung / eSpeak / etc.)",
        sizeHint = "0 MB — synthesis happens via Android's TextToSpeech framework",
        defaultEnabled = true,
        // The OS engine runs locally (no network for Google's
        // offline voices, no network for Samsung TTS), so we
        // classify Local. A handful of Google network-tier voices
        // do require connectivity; future work could split tier
        // chips per-voice — keep this Local for now so the family
        // card reads honestly about the common case.
        engineFamily = VoiceEngineFamily.Local,
    )

    val PIPER = VoiceFamilyDescriptor(
        id = VoiceFamilyIds.PIPER,
        displayName = "Piper",
        description = "Local neural voices · per-voice ONNX download",
        sourceUrl = "https://github.com/rhasspy/piper-voices",
        license = "MIT (sherpa-onnx) · CC-BY / CC0 voice datasets",
        sizeHint = "~14–30 MB per voice (low / medium tier), ~120 MB high tier",
        defaultEnabled = true,
        engineFamily = VoiceEngineFamily.Local,
    )

    val KOKORO = VoiceFamilyDescriptor(
        id = VoiceFamilyIds.KOKORO,
        displayName = "Kokoro",
        description = "Local multi-speaker · one shared ~330 MB model",
        sourceUrl = "https://huggingface.co/hexgrad/Kokoro-82M",
        license = "Apache 2.0",
        sizeHint = "~330 MB single download, 53 speakers share the model",
        defaultEnabled = true,
        engineFamily = VoiceEngineFamily.Local,
    )

    val KITTEN = VoiceFamilyDescriptor(
        id = VoiceFamilyIds.KITTEN,
        displayName = "KittenTTS",
        description = "Local lightweight · ~25 MB shared, 8 en_US speakers",
        sourceUrl = "https://github.com/KittenML/KittenTTS",
        license = "Apache 2.0",
        sizeHint = "~25 MB shared model, 8 en_US speakers (Bella, Luna, Rosie, Kiki / Jasper, Bruno, Hugo, Leo)",
        defaultEnabled = true,
        engineFamily = VoiceEngineFamily.Local,
    )

    /** Issue #1114 / #1236 — Supertonic 3. Gated on [VoiceCatalog.SUPERTONIC_ENABLED]
     *  by [VoiceFamilyRegistry] (and by the catalog), so one flag re-gates
     *  both the family card and the voices. */
    val SUPERTONIC = VoiceFamilyDescriptor(
        id = VoiceFamilyIds.SUPERTONIC,
        displayName = "Supertonic 3",
        description = "Local high-quality · shared model, 10 en_US speakers",
        sourceUrl = "https://github.com/k2-fsa/sherpa-onnx",
        license = "Apache 2.0",
        sizeHint = "~TBD MB shared model, 10 en_US speakers (F1–F5 / M1–M5)",
        defaultEnabled = true,
        engineFamily = VoiceEngineFamily.Local,
    )

    val AZURE = VoiceFamilyDescriptor(
        id = VoiceFamilyIds.AZURE,
        displayName = "Azure HD voices",
        description = "Cloud · BYOK · Dragon HD + Multilingual + Neural tiers",
        sourceUrl = "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support",
        license = "Proprietary · billed by Azure ($30 / 1M chars)",
        sizeHint = "0 MB local — synthesis happens server-side",
        requiresConfiguration = true,
        // Default OFF so a fresh install doesn't pretend to have Azure
        // voices ready; the user opts in by configuring credentials.
        defaultEnabled = false,
        engineFamily = VoiceEngineFamily.Cloud,
    )

    /** Not an engine — the muted "next thing that lands here" card.
     *  Stays owned by [VoiceFamilyRegistry], never a `VoiceEnginePlugin`. */
    val VOXSHERPA_PLACEHOLDER = VoiceFamilyDescriptor(
        id = VoiceFamilyIds.VOXSHERPA_UPSTREAMS,
        displayName = "VoxSherpa upstreams",
        description = "Placeholder for future engine-lib voice families",
        sourceUrl = "https://github.com/techempower-org/VoxSherpa-TTS",
        license = "—",
        sizeHint = "—",
        isPlaceholder = true,
        defaultEnabled = false,
        engineFamily = VoiceEngineFamily.Local,
    )
}
