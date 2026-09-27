#!/usr/bin/env bash
# check-voice-urls.sh — verify every voice-download / attribution URL the app
# ships actually resolves (HTTP 200 after redirects).
#
# Why: installed APKs download voice models from hard-coded GitHub-release /
# Hugging Face URLs. If an asset is renamed, never uploaded, or a repo moves,
# nothing fails at build time — users just get a download error. That
# happened: the Kitten v0.8 assets were never uploaded to voices-v2, so every
# Kitten download 404'd in production.
#
# What it checks:
#   1. Every "https://..." string literal under the voice package
#      (VoiceCatalog / VoiceManager / VoiceFamilyDescriptors, ...).
#   2. The Supertonic bundle: VoiceManager builds `$supertonicBaseUrl/$name`
#      at runtime for each SupertonicEngine.MODEL_FILES entry, so the bare
#      base URL is expanded with the FILE_* names read from the pinned
#      VoxSherpa-TTS fork tag's SupertonicEngine.java (the same source the
#      AAR is built from).
#
# Usage: scripts/check-voice-urls.sh            (exit 1 if any URL fails)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VOICE_DIR="$ROOT/core-playback/src/main/kotlin/in/jphe/storyvox/playback/voice"
BUILD_FILE="$ROOT/core-playback/build.gradle.kts"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# --- 1. Literal URLs -------------------------------------------------------
grep -rhoE '"https://[^"$ ]+"' "$VOICE_DIR" | tr -d '"' | sort -u > "$tmp/literal.txt"

# --- 2. Supertonic bundle expansion ----------------------------------------
# The base URL is the literal assigned to `supertonicBaseUrl`.
ST_BASE="$(grep -rhA1 'val supertonicBaseUrl' "$VOICE_DIR" | grep -oE 'https://[^"]+' | head -n1 || true)"
: > "$tmp/expanded.txt"
if [ -n "$ST_BASE" ]; then
  PIN="$(grep -oE 'VoxSherpa-TTS:v[0-9]+\.[0-9]+(\.[0-9]+)?' "$BUILD_FILE" | head -n1 | sed 's/.*://')"
  SRC="https://raw.githubusercontent.com/techempower-org/VoxSherpa-TTS/${PIN}/app/src/main/java/com/CodeBySonu/VoxSherpa/SupertonicEngine.java"
  if ! curl -fsSL --retry 2 "$SRC" -o "$tmp/SupertonicEngine.java"; then
    echo "FAIL  could not fetch SupertonicEngine.java for pin $PIN ($SRC)"
    exit 1
  fi
  grep -oE 'public static final String FILE_[A-Z_]+ *= *"[^"]+"' "$tmp/SupertonicEngine.java" \
    | sed -E 's/.*"([^"]+)"/\1/' > "$tmp/st-files.txt"
  if [ "$(wc -l < "$tmp/st-files.txt")" -lt 1 ]; then
    echo "FAIL  no FILE_* constants parsed from SupertonicEngine.java@$PIN"
    exit 1
  fi
  sed "s#^#${ST_BASE}/#" "$tmp/st-files.txt" > "$tmp/expanded.txt"
  # The bare base is a prefix, not a downloadable asset — don't HEAD it.
  grep -vxF "$ST_BASE" "$tmp/literal.txt" > "$tmp/literal2.txt" || true
  mv "$tmp/literal2.txt" "$tmp/literal.txt"
  echo "Supertonic: expanded $(wc -l < "$tmp/st-files.txt") bundle files from VoxSherpa-TTS@$PIN"
fi

sort -u "$tmp/literal.txt" "$tmp/expanded.txt" > "$tmp/all.txt"
echo "Checking $(wc -l < "$tmp/all.txt") URLs..."

# --- Probe -----------------------------------------------------------------
# HEAD first; some hosts reject HEAD, so fall back to a 1-byte ranged GET.
probe() {
  local url="$1" code
  code="$(curl -sIL --retry 2 --max-time 60 -o /dev/null -w '%{http_code}' "$url" || echo 000)"
  if [ "$code" != 200 ]; then
    code="$(curl -sL --retry 2 --max-time 60 -r 0-0 -o /dev/null -w '%{http_code}' "$url" || echo 000)"
    [ "$code" = 206 ] && code=200
  fi
  echo "$code $url"
}
export -f probe
xargs -P 8 -I{} bash -c 'probe "$1"' _ {} < "$tmp/all.txt" | sort > "$tmp/results.txt"

FAILED="$(grep -v '^200 ' "$tmp/results.txt" || true)"
echo "OK: $(grep -c '^200 ' "$tmp/results.txt" || true)"
if [ -n "$FAILED" ]; then
  echo "FAILED:"
  echo "$FAILED" | sed 's/^/  /'
  exit 1
fi
echo "All voice URLs resolve."
