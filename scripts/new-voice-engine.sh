#!/usr/bin/env bash
#
# new-voice-engine.sh — scaffold a new Candela voice-engine plugin.
#
#   scripts/new-voice-engine.sh <id> "<Display Name>"
#   e.g. scripts/new-voice-engine.sh whisper "Whisper TTS"
#
# Generates, inside :core-playback (voice engines are in-module by design —
# they wrap native/framework synth the playback layer owns):
#   voice/engines/<Pascal>EnginePlugin.kt      @VoicePlugin("voice_<id>"), every member stubbed
#   test/.../engines/<Pascal>EnginePluginContractTest.kt   wired to the shared kit
#
# ZERO other edits — @VoicePlugin makes KSP emit the Hilt binding and
# VoiceEngineRegistry picks it up from the multibinding; CI's Build APK
# proves it. See docs/CONTRIBUTING-VOICES.md for the walkthrough.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: scripts/new-voice-engine.sh <id> \"<Display Name>\"" >&2
    echo "  e.g. scripts/new-voice-engine.sh whisper \"Whisper TTS\"" >&2
    exit 2
fi

ID="$1"
DISPLAY="$2"

LOWER="$(printf '%s' "$ID" | tr -cd '[:alnum:]' | tr '[:upper:]' '[:lower:]')"
PASCAL="$(printf '%s' "$DISPLAY" | sed 's/[^[:alnum:] ]//g' \
    | awk '{for(i=1;i<=NF;i++){$i=toupper(substr($i,1,1)) tolower(substr($i,2))}; $1=$1; print}' \
    | tr -d ' ')"

if [[ -z "$LOWER" || -z "$PASCAL" ]]; then
    echo "error: could not derive names from id='$ID' display='$DISPLAY'" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINES="$REPO_ROOT/core-playback/src/main/kotlin/in/jphe/storyvox/playback/voice/engines"
TESTS="$REPO_ROOT/core-playback/src/test/kotlin/in/jphe/storyvox/playback/voice/engines"
PLUGIN_FILE="$ENGINES/${PASCAL}EnginePlugin.kt"
TEST_FILE="$TESTS/${PASCAL}EnginePluginContractTest.kt"

if [[ -e "$PLUGIN_FILE" ]]; then
    echo "error: $PLUGIN_FILE already exists" >&2
    exit 1
fi

mkdir -p "$ENGINES" "$TESTS"

cat > "$PLUGIN_FILE" <<'PLUGIN'
package `in`.jphe.storyvox.playback.voice.engines

import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoicePlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __DISPLAY__ voice engine (scaffolded by scripts/new-voice-engine.sh).
 *
 * Every member is an honest stub — fill them in against your engine
 * library, guided by docs/CONTRIBUTING-VOICES.md:
 *  - synth: [generateAudioPCM] (hold `EngineMutex.mutex` at call sites —
 *    the contract kdoc on [VoiceEnginePlugin] is law)
 *  - model loading: override `modelSpec`/`loadModel` ([`in`.jphe.storyvox
 *    .playback.voice.ModelSpec] describes what you need on disk)
 *  - catalog: [catalogEntries] + [familyDescriptor] (the Plugin Manager card)
 *  - parallel synth (optional): implement
 *    [`in`.jphe.storyvox.playback.voice.StreamingSynth]
 *
 * NOTE [handles] returns false: new engines are DE-SEALED — they have no
 * `EngineType` variant. Dispatch reaches this plugin via
 * `VoiceEngineRegistry.byKey(EngineKey("voice___LOWER__"))`, not via the
 * legacy sealed `when`. That is the point of the epic.
 */
@VoicePlugin("voice___LOWER__")
@Singleton
class __PASCAL__EnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = "voice___LOWER__"

    /** Nominal until your engine reports a real rate. */
    override val sampleRate: Int = 22_050

    /** Flip to true only when [generateAudioPCM] renders real PCM
     *  synchronously (offline export path). */
    override val supportsExport: Boolean = false

    /** De-sealed engines have no EngineType variant — see class kdoc. */
    override fun handles(type: EngineType): Boolean = false

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = null

    override fun catalogEntries(): List<CatalogEntry> = emptyList()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptor(
        id = "voice___LOWER__",
        displayName = "__DISPLAY__",
        description = "One-line engine description (fill me in)",
        sourceUrl = "https://example.com",
        license = "TODO license",
        sizeHint = "~0 MB",
        defaultEnabled = false,
    )
}
PLUGIN

cat > "$TEST_FILE" <<'CTEST'
package `in`.jphe.storyvox.playback.voice.engines

import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.testkit.voice.VoiceEnginePluginContractTest

/**
 * __DISPLAY__ against the shared voice-engine contract kit. Green from
 * the first run (the stub's metadata is coherent); KEEP it green as you
 * fill the plugin in. CI's Build APK proves the @VoicePlugin Hilt
 * binding; on-device QA proves audio.
 */
class __PASCAL__EnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = __PASCAL__EnginePlugin()
    override fun sampleKeys(): List<EngineKey> = listOf(EngineKey("voice___LOWER__"))
}
CTEST

for f in "$PLUGIN_FILE" "$TEST_FILE"; do
    sed -i \
        -e "s|__PASCAL__|$PASCAL|g" \
        -e "s|__LOWER__|$LOWER|g" \
        -e "s|__DISPLAY__|$DISPLAY|g" \
        "$f"
done

cat <<DONE
Scaffolded ${PASCAL}EnginePlugin (engineId "voice_$LOWER") in :core-playback.

ZERO other edits — the @VoicePlugin annotation generates your Hilt binding
(KSP) and VoiceEngineRegistry discovers it; CI's Build APK is the proof.
Then: ./gradlew :core-playback:testDebugUnitTest --tests "*${PASCAL}EnginePluginContractTest*"
Guide: docs/CONTRIBUTING-VOICES.md
DONE
