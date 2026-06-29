# Play Console Upload Prerequisites

_Assessed 2026-06-29 against `app/build.gradle.kts` (v1.4.5 / vc247), `app/src/main/play/`._

## TL;DR

The `gradle-play-publisher` plugin is **fully configured with safe defaults**, the **listing metadata + graphics are in place**, and the **`applicationId` is correct** (`org.techempower.candela`). Two things gate the first upload:

1. ❌ **Service-account JSON is missing** — blocks *automated* (`publishReleaseBundle`) uploads. Manual Console uploads don't need it.
2. ⚠️ **The first upload must be done manually** regardless — the Google Play Developer API cannot *create* a new app, so the Console app + first release must be created by hand; only then can the plugin automate subsequent releases.

## 1. Plugin & `play {}` config — ✅ configured

- Plugin applied: `com.github.triplet.play` **v4.0.0** (`alias(libs.plugins.play.publisher)`, `app/build.gradle.kts` L26).
- `play {}` block (L524–537), deliberately conservative:
  - `track = "internal"` — uploads land in **Internal Test**, never auto-Production.
  - `releaseStatus = DRAFT` — staged, requires manual "Release" click.
  - `defaultToAppBundles = true` — **AAB** is the canonical artifact.
  - `commit = false` — builds a Play "edit" without publishing; an explicit `publishApps` commits, so a failed run never half-publishes.
  - `resolutionStrategy = IGNORE` — versionCode is source of truth; overrides stale half-edits.
  - `serviceAccountCredentials` is set **only if** `hasPlayPublisherCredentials` (path present + file exists). Absent credentials → tasks no-op with "PlayPublisher requires credentials"; rest of build unaffected.

## 2. Service-account JSON — ❌ MISSING (the main gap for automation)

- Read from `storyvox.playPublisher.credentialsFile` in `local.properties` (L173–174); gated by `hasPlayPublisherCredentials` (L176–177).
- **Not set in either `local.properties`** (candela or storyvox copies) and **no `*.json` in `~/.storyvox-keystore/`.** So `hasPlayPublisherCredentials = false` → no automated upload possible yet.
- **To enable automated uploads:**
  1. In Google Cloud, create a service account; in Play Console (Setup → API access) link it and grant the **Release Manager** role (per `docs/play-store/RUNBOOK.md`).
  2. Download its JSON key → place at e.g. `~/.storyvox-keystore/play-service-account.json` (gitignored location).
  3. Add `storyvox.playPublisher.credentialsFile=/home/jp/.storyvox-keystore/play-service-account.json` to `local.properties` (and seed it onto the katana CI runner like the keystore props).

## 3. Listing assets (`app/src/main/play/`) — ✅ present

Triple-T's expected layout is populated for `en-US`:

| Asset | Status |
|---|---|
| `release-notes/en-US/default.txt` | ✅ 519 chars |
| `listings/en-US/title.txt` | ✅ 34 bytes — ⚠️ **verify ≤30 chars** (Play's title limit; ~33 chars incl. newline may be over — see listing-copy task #85) |
| `listings/en-US/short-description.txt` | ✅ 77 bytes (≤80 limit ✓) |
| `listings/en-US/full-description.txt` | ✅ 2773 chars (≤4000 ✓) |
| `graphics/icon/01.png` | ✅ (verify 512×512, ≤1 MB) |
| `graphics/feature-graphic/01.png` | ✅ (verify 1024×500) |
| `graphics/phone-screenshots/` | ✅ 4 images (Play needs ≥2) |
| `graphics/tablet-screenshots/` | ✅ 6 images (optional) |

These feed the `publishListing`/upload path automatically once credentials exist. (Image *dimensions* not verified here — flagged for the listing tasks.)

## 4. `applicationId` — ✅ correct

- `applicationId = "org.techempower.candela"` (L184) — what Play Console will key on. ✅
- `namespace = "in.jphe.storyvox"` (L180) is just the **code package** (internal, unaffected by Play) — fine, no change needed.
- ⚠️ The Play Console app **must be created under `org.techempower.candela`**. Note the publisher property names are still `storyvox.*` (pre-rebrand) — functional, but confirm intentional.

## What's needed for the first upload (ordered)

1. **Create the app in Play Console** under `org.techempower.candela` (manual, one-time).
2. **Build the AAB:** `./gradlew :app:bundleRelease` on katana (release keystore + props present — see `docs/play-store-signing.md`).
3. **Upload that AAB manually** to the Internal Test track (the Play API can't create the initial app/release).
4. **Enroll in Play App Signing** (recommended; `storyvox-release.keystore` = upload key).
5. **Then enable automation (optional):** add the service-account JSON (§2) → `./gradlew publishReleaseBundle` handles subsequent uploads (internal/draft).

**Already done:** plugin config, listing text + graphics, correct applicationId, release keystore (per `docs/play-store-signing.md`).
**Outstanding:** service-account JSON (automation), manual creation of the Console app + first manual AAB upload, and the listing-asset spot-checks (title length, image dimensions).

_Related: `docs/play-store-signing.md`, `docs/play-store/RUNBOOK.md`, `docs/play-store-listing.md`, `docs/play-store-policy-check.md`._
