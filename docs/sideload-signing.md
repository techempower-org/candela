---
layout: default
title: Sideload signing key rotation
description: How the GitHub sideload APKs (phone + Wear) rotate off the public debug key with APK Signature Scheme v3, without forcing an uninstall.
---

# Sideload signing key rotation (#1756)

The phone and Wear APKs attached to GitHub Releases were signed only with
`app/storyvox-debug.keystore`. That key is public (checked in, password
`android`), so it can never be registered for Android developer verification:
registering it would let anyone sign an APK as TechEMPOWER's Candela.

Replacing the key outright would force every sideload user to uninstall, which
wipes their library, downloads and notes. Instead the sideload APKs use
**APK Signature Scheme v3 key rotation**.

## How it works

| Block | Signer | Who verifies it |
|---|---|---|
| v2 | old debug key `androiddebugkey` — SHA-256 `b57a27ec…e8131e06` | Android 8.0–8.1 (API 26–27) |
| v3 + lineage | new `candela-sideload` key — SHA-256 `270efc1c…7b977ca0`, RSA 4096, valid to 2056 | Android 9+ (API 28+) |

`app/candela.lineage` is the proof-of-rotation: the new certificate, signed by
the old key. It contains only public certificates and is committed. Because the
old key vouches for the new one, an installed old-key build accepts the rotated
build as an in-place upgrade (`adb install -r`, or tapping the new APK), and
phone + Wear keep sharing one identity for the Data Layer bridge.

The old signer's lineage capabilities: installed data, shared UID, permission
and auth are granted; **rollback is not**. Once a device holds a rotated build,
an unrotated build no longer installs over it. That is why the CI step refuses
to publish a tag without the signing secrets.

## Where the key lives

- **Vaultwarden** item `candela-sideload-keystore`: the store password (= key
  password), alias `candela-sideload`, and the keystore file as an attachment.
- **Local copy** on katana: `~/.storyvox-keystore/candela-sideload.keystore`.
- **Actions secrets**: `CANDELA_SIDELOAD_KEYSTORE_B64` (base64 keystore),
  `CANDELA_SIDELOAD_KEYSTORE_PASSWORD`, `CANDELA_SIDELOAD_KEY_ALIAS`.

Never commit the keystore. The Play upload key (`storyvox-release.keystore`) is
a separate key; see [release-keystore.md](release-keystore.md).

## CI

`android.yml` → **Build APK** runs `scripts/resign-sideload.sh` after both
`assembleRelease` steps. The script re-signs each APK in place with
`apksigner sign --lineage app/candela.lineage --rotation-min-sdk-version 28`
using both signers, then checks that the v3 signer is the new key and the
API 26–27 view is the old key, and fails otherwise.

- Fork and Dependabot PRs have no secrets: the step logs a notice and skips.
  The APKs keep the debug-only signature and the build still gates the PR.
- Tag builds without the secrets fail. Shipping an unrotated release would
  break upgrades for users who already hold a rotated one.

## Re-signing by hand

```bash
export CANDELA_SIDELOAD_KEYSTORE=~/.storyvox-keystore/candela-sideload.keystore
CANDELA_SIDELOAD_KEYSTORE_PASSWORD="$(bw get password candela-sideload-keystore)"
export CANDELA_SIDELOAD_KEYSTORE_PASSWORD
export APKSIGNER=~/Android/Sdk/build-tools/37.0.0/apksigner
./scripts/resign-sideload.sh candela-vX.Y.Z.apk candela-wear-vX.Y.Z.apk
apksigner verify --print-certs -v candela-vX.Y.Z.apk
```

## Remaining steps (tracked in #1756)

1. Upgrade-in-place test on a phone and on the watch: install the current
   release, then `adb install -r` the rotated build and confirm the library
   survives.
2. Register the new key's SHA-256
   (`27:0E:FC:1C:C5:EA:77:B3:81:45:FF:5C:42:46:23:DA:1A:69:AE:1F:79:01:41:4F:98:5F:AA:3A:7B:97:7C:A0`)
   in Play Console → Android developer verification → Candela → Add key.
3. After a few releases, retire the checked-in debug keystore from signing.
   apksigner needs the lineage's oldest signer for any v1/v2 block, so this
   means going v3-only, which in turn means `minSdk` 28. Keep the lineage file
   either way.
