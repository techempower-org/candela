#!/usr/bin/env bash
# Re-sign sideload APKs with APK Signature Scheme v3 key rotation (#1756).
#
#   ./scripts/resign-sideload.sh <apk> [<apk> ...]
#
# Each APK is re-signed IN PLACE with both signers from app/candela.lineage:
#   - v2 block: the original checked-in debug key (androiddebugkey, B5:7A:27:EC…)
#               — what API 26–27 devices verify.
#   - v3 block: the private sideload key (candela-sideload, 27:0E:FC:1C…) plus the
#               lineage proving the old key vouched for it — what API 28+ verify.
# Existing installs therefore upgrade in place (no uninstall, no data loss), and
# phone + Wear keep sharing one signing identity for the Data Layer bridge.
#
# Environment:
#   CANDELA_SIDELOAD_KEYSTORE           path to candela-sideload.keystore (PKCS12)
#   CANDELA_SIDELOAD_KEYSTORE_PASSWORD  store password (= key password); read via
#                                       apksigner's env: indirection, never argv
#   CANDELA_SIDELOAD_KEY_ALIAS          optional, default "candela-sideload"
#   APKSIGNER                           optional path to apksigner (build-tools >= 33);
#                                       default: newest under $ANDROID_HOME/build-tools
#
# The keystore lives in Vaultwarden ("candela-sideload-keystore") and in the
# CANDELA_SIDELOAD_KEYSTORE_B64 Actions secret — never in the repo. The lineage
# file holds only public certificates and is safe to commit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINEAGE="$ROOT/app/candela.lineage"
OLD_KS="$ROOT/app/storyvox-debug.keystore"
ALIAS="${CANDELA_SIDELOAD_KEY_ALIAS:-candela-sideload}"

# Pinned certificate SHA-256 digests. A mismatch means the wrong key or lineage
# reached the signer — fail rather than ship an APK that cannot upgrade.
OLD_SHA=b57a27ec1647004efaa5f48e42344965acb4f611a7dead4e0417be04e8131e06
NEW_SHA=270efc1cc5ea77b38145ff5c424623da1a69ae1f7901414f985faa3a7b977ca0

[ "$#" -gt 0 ] || { echo "usage: $0 <apk> [<apk> ...]" >&2; exit 2; }
: "${CANDELA_SIDELOAD_KEYSTORE:?set CANDELA_SIDELOAD_KEYSTORE to the keystore path}"
: "${CANDELA_SIDELOAD_KEYSTORE_PASSWORD:?set CANDELA_SIDELOAD_KEYSTORE_PASSWORD}"
[ -f "$CANDELA_SIDELOAD_KEYSTORE" ] || { echo "keystore not found: $CANDELA_SIDELOAD_KEYSTORE" >&2; exit 2; }
[ -f "$LINEAGE" ] || { echo "lineage not found: $LINEAGE" >&2; exit 2; }

if [ -z "${APKSIGNER:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$SDK" ] || { echo "set APKSIGNER or ANDROID_HOME" >&2; exit 2; }
  BT="$(ls "$SDK/build-tools" | sort -V | tail -1)"
  APKSIGNER="$SDK/build-tools/$BT/apksigner"
fi
[ -x "$APKSIGNER" ] || { echo "apksigner not executable: $APKSIGNER" >&2; exit 2; }

for apk in "$@"; do
  [ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 2; }
  tmp="$apk.rotated.tmp"
  rm -f "$tmp"
  "$APKSIGNER" sign \
    --in "$apk" --out "$tmp" \
    --lineage "$LINEAGE" \
    --rotation-min-sdk-version 28 \
    --v4-signing-enabled false \
    --ks "$OLD_KS" --ks-key-alias androiddebugkey --ks-pass pass:android \
    --next-signer \
    --ks "$CANDELA_SIDELOAD_KEYSTORE" --ks-key-alias "$ALIAS" \
    --ks-pass env:CANDELA_SIDELOAD_KEYSTORE_PASSWORD

  # API 28+ view: must verify via v3 with the NEW key.
  v3="$("$APKSIGNER" verify --print-certs -v "$tmp" 2>/dev/null)"
  # API 26–27 view: must verify via v2 with the OLD key.
  v2="$("$APKSIGNER" verify --print-certs -v --min-sdk-version 26 --max-sdk-version 27 "$tmp" 2>/dev/null)"
  grep -q '^Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$v3" \
    || { echo "FAIL $apk: v3 signature missing" >&2; rm -f "$tmp"; exit 1; }
  grep -q "^V3.0 Signer: certificate SHA-256 digest: $NEW_SHA" <<<"$v3" \
    || { echo "FAIL $apk: v3 signer is not the candela-sideload key" >&2; rm -f "$tmp"; exit 1; }
  grep -q "^V2 Signer: certificate SHA-256 digest: $OLD_SHA" <<<"$v2" \
    || { echo "FAIL $apk: v2 signer is not the original debug key" >&2; rm -f "$tmp"; exit 1; }

  mv -f "$tmp" "$apk"
  echo "re-signed $apk  (v2 old=${OLD_SHA:0:16}…  v3 new=${NEW_SHA:0:16}…, rotation-min-sdk 28)"
done
