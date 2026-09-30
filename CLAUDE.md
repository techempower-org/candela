# Candela

Android app that turns text from 37 sources into narrated audiobooks via TTS. Kotlin, Jetpack Compose, Hilt, Room, OkHttp.

## Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :feature:compileDebugKotlin # fast compile check (no APK)
./gradlew :source-ao3:testDebugUnitTest  # single module tests
./gradlew testDebugUnitTest           # all tests
```

CI runs on **GitHub-hosted runners** (`ubuntu-latest`; free for this public repo — switched 2026-09-06 from the self-hosted katana/familiar/ubox0 pool, which stays registered but idle). Tags build the two sideload APKs (phone + Wear, debug-signed for upgrade continuity #952, then re-signed with v3 key rotation to the private `candela-sideload` key — `scripts/resign-sideload.sh` + `app/candela.lineage`, secrets `CANDELA_SIDELOAD_*`, tag builds fail without them; `docs/sideload-signing.md`, #1756) and publish the GitHub release. CI reads the optional OAuth client ids from Actions secrets; **the release keystore is NOT in CI and the Play AAB is never a GitHub asset** — an AAB is not installable by anyone, only Play consumes it. Build it on **familiar** when submitting (`ssh familiar`: shallow-clone the tag, `local.properties` = sdk.dir only, JDK 21, `./gradlew :app:bundleRelease --no-daemon`, a separate invocation from assembleRelease, #952; NDK 29.0.14206865 must be installed there for the #1691 debug-symbols gate). Then on katana: strip `META-INF/*.SF|*.RSA` and `jarsigner` it with the upload key from `local.properties` `storyvox.release*`, verify with `keytool -printcert -jarfile` (SHA256 38:9F:BD…), and upload in Play Console. The keystore never leaves katana. A hosted `Build APK` takes ~20 min cold. Never compile on katana (a guard blocks gradle builds there since 2026-09-29, after a bundleRelease pushed katana into swap and the reaper killed other lanes). Push and let CI be the compile gate; run local tests on familiar.

## Module layout

- **app** — nav graph (`StoryvoxNavHost`), DI wiring (`AppBindings`), `SettingsRepositoryUiImpl`
- **feature** — all UI: `browse/`, `reader/`, `ocr/`, `library/`, `settings/`, `chat/`, `fiction/`, `voicelibrary/`, `onboarding/`, `follows/`, `techempower/`, `auth/` (source logins; no Candela account), `notes/`, `briefing/`, `feed/`, `stats/`, `sessions/`, `debug/`, `milestone/`, `engine/`, `docs/` (in-app Handbook), plus shared `api/`, `components/`, `di/`
- **core-data** — `FictionSource` interface, `SearchQuery`, `FilterDimension`/`FilterState`, Room DB, models
- **core-playback** — TTS engine (`EnginePlayer`), voice catalog, audio focus
- **core-llm** — AI chat, summaries
- **core-ui** — shared theme, spacing, composables
- **core-plugin-ksp** — `@SourcePlugin` annotation processor → Hilt `@IntoSet` factories
- **wear** — Wear OS companion app (Library Nocturne on the watch)
- **baselineprofile** — Macrobenchmark module that generates the R8 baseline profile
- **source-*** — 37 source modules; 34 implement `FictionSource` (37 registered sources — `source-notion` registers PAT + TechEMPOWER, and `source-slack` and `source-telegram` each register two). The other 3 reuse the module pattern without it: `source-azure` (Azure HD cloud-voice backend), `source-epub-writer` and `source-audiobook-writer` (export writers)

## Key patterns

**Source plugin contract**: Each source module has a `*Source.kt` implementing `FictionSource`. Annotated with `@SourcePlugin` — KSP generates Hilt bindings into `SourcePluginRegistry`. To add a source: run `scripts/new-source.sh <id> "<Display Name>"` (generates module + di module + contract test), make the two printed one-line edits, implement the API. Contract kit: `core-source-testkit` (`FictionSourceContractTest` — IO pin, auth mapping, CF detection). Guide: `docs/CONTRIBUTING-SOURCES.md`. Don't add `SourceIds` entries — the annotation `id` is the source of truth.

**Voice plugin contract**: Engines live in `core-playback/.../voice/engines/`, implement `VoiceEnginePlugin`, and are annotated `@VoicePlugin(engineId)` — KSP generates the Hilt bindings (no hand DI module). Model loading is data-driven via `ModelSpec` (`modelSpec()`/`loadModel()` on the plugin); `EngineKey` is the de-sealed discriminator that catalog rows (`CatalogEntry`/`UiVoiceInfo`) carry; `EngineType` is a typed view of it — the six built-ins keep variants, every other engine is `EngineType.Plugin(key)`, and each dispatch site (EnginePlayer load/pool/synth, recap, export, prerender, Voice Library) has one generic `Plugin` arm, so a new engine needs NO central edits (#1500/#1501). Voice Library labels/colours/order come from `VoiceFamilyDescriptor.presentation`. `StreamingSynth` is the optional pooled-parallel-synth capability. To add an engine: run `scripts/new-voice-engine.sh <id>` (plugin class + contract test; DI is zero-edit — CI proves the Hilt binding). Contract kit: `VoiceEnginePluginContractTest` in `core-voice-testkit` (split out of `core-source-testkit` in #1504). Guide: `docs/CONTRIBUTING-VOICES.md`.

**Browse filters**: Sources declare `filterDimensions()` returning `List<FilterDimension>` (Sort, Select, TagSet, NumberRange, DateRange, Toggle, Text). `DynamicFilterSheet` renders them generically. Sources implement `applyFilters(base, state)` to translate UI state → `SearchQuery`.

**Navigation**: `StoryvoxNavHost.kt` defines all routes as `StoryvoxRoutes` constants. Bottom bar: Playing, Library, Browse, Voices, Settings.

**Testing**: JUnit 4. Mostly plain JVM tests with hand-rolled fakes (see `PluginManagerLogicTest` for the pattern); `core-playback`/`feature` carry some Robolectric classes — their SDK-36 sandboxes need Java 21 (JDK 17 fails at classMethod with "Android SDK 36 requires Java 21"; run tests locally on JDK 21). CI's `Unit Tests` job runs every module's `testDebugUnitTest` on JDK 21 on PRs and main. Compose UI tests use `createComposeRule()`. New sources/engines subclass the contract kits in `core-source-testkit`.

## Large files (read with offset/limit)

- `EnginePlayer.kt` — ~6300 lines
- `SettingsScreen.kt` — ~4100 lines (legacy long-scroll, being replaced by hub)
- `SettingsRepositoryUiImpl.kt` — ~3700 lines
- `UiContracts.kt` — ~2600 lines
- `AudiobookView.kt` — ~2600 lines

## Versioning

`app/build.gradle.kts` — `versionName` (semver) + `versionCode` (monotonic int). Bump both for every tagged release. realm-sigil provides deterministic build names.

## Ship pipeline

commit → push → PR → CI green → merge → version bump on main → tag → CI builds APK → download → install on R83W80CAFZB + R5CRB0W66MK → phone check → Slack

**CI wait**: Use ONE `run_in_background` call, never poll manually:
```bash
until gh run view $RUN_ID --json status --jq '.status' | grep -q completed; do sleep 30; done
```

**Version bump**: Edit `app/build.gradle.kts` — increment both `versionCode` and `versionName`. Commit as `chore(release): v$VERSION — $tagline`. Tag and push in one shot: `git push && git tag v$VERSION && git push origin v$VERSION`.

**Phone check**: Run `./scripts/phone-check.sh <serial> <versionCode>` on both devices. NOT a subagent — the script is 30 lines and costs ~500 tokens vs ~39K for an agent.

**Slack**: `~/.claude/scripts/slack-storyvox.sh '<message>'` — takes TEXT, not files. Template structure:
```
:candle: *storyvox $VERSION* — $tagline
✦  _$SIGIL_NAME_  ✦
> $poetic_line
_TechEMPOWER — Technology for All. Access Made Easy._ (links)
*What's new* (feature bullets with emoji from docs/slack-release-template.md palette)
*Under the hood* (infra/test bullets)
*Install* (APK link, release link, compare link)
_Installed on ... · clean launch._
:hammer_and_wrench:  realm `fantasy`  ·  built `$time`  ·  commit `$hash`
```
Sigil: `python3 -c "..."` with realm-sigil word lists from `~/Projects/realm-sigil/words/realms.json`.
