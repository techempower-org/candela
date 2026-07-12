# App Signing — Play Store Readiness Assessment

_Assessed 2026-06-29 against `app/build.gradle.kts` (versionCode 247 / v1.4.5) and `.github/workflows/android.yml`. Current release: **v1.6.3 / vc257** — re-verify if the signing config changed._

## TL;DR

**Not a blocker — the release keystore already exists and is wired up.** Candela uses a deliberate **two-keystore split**:

- The **sideload APK** shipped on GitHub Releases (`:app:assembleRelease`) is **debug-signed on purpose** (upgrade continuity for existing sideload users). This artifact is *not* for Play and Google would reject it.
- The **Play artifact** (`:app:bundleRelease` AAB) is configured to sign with a **real RSA-4096 release keystore** that already exists on disk, is backed up in Vaultwarden, and is valid until 2053.

So no keystore needs to be generated. Reaching Play upload is a matter of building/uploading the **AAB** (not the APK) and choosing the Play App Signing enrollment model.

## How signing is wired (`app/build.gradle.kts`)

Two signing configs (`signingConfigs {}`, ~L207–250):

| Config | Source | Details |
|---|---|---|
| `debug` | checked-in `app/storyvox-debug.keystore` | storepass `android`, alias `androiddebugkey` — the **canonical Android debug keystore**. SHA-256 `B5:7A:27:EC:…:1E:06`. Gitignore explicitly allowlists it (`!app/storyvox-debug.keystore`). |
| `release` | `~/.storyvox-keystore/storyvox-release.keystore` via `local.properties` props (`storyvox.releaseStoreFile/StorePassword/KeyAlias/KeyPassword`) | Only registered when `hasReleaseKeystore` (props present **and** file exists). |

The `release` build type picks the key by task graph (L~336):

```kotlin
signingConfig = if (hasReleaseKeystore && isBundleOrPublishBuild)
    signingConfigs.getByName("release")   // bundleRelease / publishReleaseBundle → Play key
else
    signingConfigs.getByName("debug")     // assembleRelease (APK), fresh checkouts → debug key
```

- `isBundleOrPublishBuild` = the requested Gradle tasks include `bundle*`/`publish*` (the AAB/Play path).
- `hasReleaseKeystore` = the 4 `storyvox.release*` props are set **and** the keystore file is present.

This split (issue **#952**) is intentional: flipping the *APK* to the release key would break `adb install` upgrades for every sideload user since v0.4.15, while Play needs a non-debug key. The two channels carry different certs by design and don't cross-upgrade.

## The release keystore (already exists)

`~/.storyvox-keystore/storyvox-release.keystore` — present (4.4 KB, generated 2026-05-16):

- **RSA 4096**, PKCS12, alias `storyvox`, validity 10000 days (**until ~2053-10-01**)
- CN=TechEMPOWER.org, OU=Storyvox, O=TechEMPOWER, Grass Valley CA US
- **SHA-256** `38:9F:BD:AA:4A:11:48:3B:…:E0:16`, SHA-1 `93:98:00:05:96:47:5A:85:…:EE:B0`
- **Backed up in Vaultwarden** item `storyvox-release-keystore` (path, alias, password, fingerprints in the note)
- **Wired** in both `local.properties` copies the CI seeds from (`/home/jp/Projects/candela` and `/home/jp/Projects/storyvox`)

`gradle-play-publisher` (Triple-T v4.0.0) is configured for upload — `publishReleaseBundle`, `releaseStatus=DRAFT`, credentials via `storyvox.playPublisher.credentialsFile` (a `play-service-account.json`).

## What CI does today (`android.yml`)

The `Build APK` job runs **`:app:assembleRelease`** only → produces the **debug-signed sideload APK** attached to each GitHub Release (release note literally says *"Signed with the project's checked-in keystore … Sideload-only distribution today."*). It seeds the `storyvox.release*` props into `local.properties` on katana, but **does not run `bundleRelease`**, so **no Play-ready AAB is produced or uploaded automatically yet.**

## Verdict & what needs to change for Play upload

**Current state: ready — release keystore in hand, no migration of signing material required.** Remaining steps:

1. **Produce the AAB** (signs with the release key automatically): on a machine/runner with the `storyvox.release*` props + keystore present (katana qualifies):
   `./gradlew :app:bundleRelease` → release-signed `.aab`.
2. **Enroll in Play App Signing (recommended).** Upload the AAB via Play Console (or `./gradlew publishReleaseBundle` with the service-account JSON). Use **`storyvox-release.keystore` as the *upload* key**; let Google hold the app signing key. This protects against key loss and is the modern default. (Self-managed app signing — uploading our key as the app signing key — also works but is riskier.)
3. **Confirm the package id / cert** Play expects matches (`org.techempower.candela`, upload-cert SHA-256 `38:9F:BD:AA…`).
4. **Optional CI automation:** add a tag-triggered `bundleRelease`/`publishReleaseBundle` job (gated on the keystore + service-account being present) if Play releases should be automatic. Not required for a first manual upload.

**No action needed on:** generating a keystore (done), keystore backup (vaulted), or the sideload APK (debug-signing there is intentional and should stay).

### One caveat to verify before first upload
The keystore + `gradle-play-publisher` config predate the **storyvox → Candela rebrand** (`applicationId` is now `org.techempower.candela`, repo `techempower-org/candela`). The keystore cert itself is application-id-agnostic, but confirm the Play Console app + the publisher config's package name target `org.techempower.candela`, and that the `storyvox.release*`/`playPublisher` property names (still `storyvox.*`) are intentional or renamed consistently.

_Related docs: `docs/release-keystore.md` (keystore generation/migration walkthrough), `docs/play-store/RUNBOOK.md`, `docs/play-store-policy-check.md`, `docs/play-store-listing.md`._
