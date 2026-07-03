# Candela

Android app that turns text from 27 sources into narrated audiobooks via TTS. Kotlin, Jetpack Compose, Hilt, Room, OkHttp.

## Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :feature:compileDebugKotlin # fast compile check (no APK)
./gradlew :source-ao3:testDebugUnitTest  # single module tests
./gradlew testDebugUnitTest           # all tests
```

CI runs on self-hosted runners (katana + familiar). Tags trigger release APK build + GitHub release.

**CI runner — familiar**: familiar sleeps when idle. If a CI run stays queued >60s, wake it:
```bash
realm wol wake familiar
```
Wait ~60s for boot, then CI picks up automatically. Never compile locally on katana — push and let CI be the compile gate.

## Module layout

- **app** — nav graph (`StoryvoxNavHost`), DI wiring (`AppBindings`), `SettingsRepositoryUiImpl`
- **feature** — all UI: `browse/`, `reader/`, `ocr/`, `library/`, `settings/`, `chat/`, `fiction/`, `voicelibrary/`, `onboarding/`, `follows/`, `techempower/`, `sync/`, `auth/`, `sessions/`, `debug/`, `milestone/`, `engine/`, plus shared `api/`, `components/`, `di/`
- **core-data** — `FictionSource` interface, `SearchQuery`, `FilterDimension`/`FilterState`, Room DB, models
- **core-playback** — TTS engine (`EnginePlayer`), voice catalog, audio focus
- **core-llm** — AI chat, summaries
- **core-sync** — InstantDB cloud sync
- **core-ui** — shared theme, spacing, composables
- **core-plugin-ksp** — `@SourcePlugin` annotation processor → Hilt `@IntoSet` factories
- **wear** — Wear OS companion app (Library Nocturne on the watch)
- **baselineprofile** — Macrobenchmark module that generates the R8 baseline profile
- **source-*** — 30 source modules; 27 implement `FictionSource`. The other 3 reuse the module pattern without it: `source-azure` (Azure HD cloud-voice backend), `source-epub-writer` and `source-audiobook-writer` (export writers)

## Key patterns

**Source plugin contract**: Each source module has a `*Source.kt` implementing `FictionSource`. Annotated with `@SourcePlugin` — KSP generates Hilt bindings into `SourcePluginRegistry`. To add a source: run `scripts/new-source.sh <id> "<Display Name>"` (generates module + di module + contract test), make the two printed one-line edits, implement the API. Contract kit: `core-source-testkit` (`FictionSourceContractTest` — IO pin, auth mapping, CF detection). Guide: `docs/CONTRIBUTING-SOURCES.md`. Don't add `SourceIds` entries — the annotation `id` is the source of truth.

**Voice plugin contract**: Engines live in `core-playback/.../voice/engines/`, implement `VoiceEnginePlugin`, and are annotated `@VoicePlugin(engineId)` — KSP generates the Hilt bindings (no hand DI module). Model loading is data-driven via `ModelSpec` (`modelSpec()`/`loadModel()` on the plugin); `EngineKey` is the de-sealed discriminator, but `EngineType` is still sealed and playback dispatch still discriminates on it — a NEW engine additionally needs the central touchpoints listed in `docs/CONTRIBUTING-VOICES.md` §7 (EngineType variant, EnginePlayer swap arm, pooled-family branches). `StreamingSynth` is the optional pooled-parallel-synth capability; its EnginePlayer consumption is per-family, not yet generic (tracked follow-up). To add an engine: run `scripts/new-voice-engine.sh <id>` (plugin class + contract test; DI is zero-edit — CI proves the Hilt binding). Contract kit: `VoiceEnginePluginContractTest` in `core-source-testkit`. Guide: `docs/CONTRIBUTING-VOICES.md`.

**Browse filters**: Sources declare `filterDimensions()` returning `List<FilterDimension>` (Sort, Select, TagSet, NumberRange, DateRange, Toggle, Text). `DynamicFilterSheet` renders them generically. Sources implement `applyFilters(base, state)` to translate UI state → `SearchQuery`.

**Navigation**: `StoryvoxNavHost.kt` defines all routes as `StoryvoxRoutes` constants. Bottom bar: Playing, Library, Browse, Voices, Settings.

**Testing**: JUnit 4. Mostly plain JVM tests with hand-rolled fakes (see `PluginManagerLogicTest` for the pattern); `core-playback`/`feature` carry some Robolectric classes — their SDK-36 sandboxes need Java 21 (JDK 17 fails at classMethod with "Android SDK 36 requires Java 21"; CI only compiles tests, so this bites local runs only). Compose UI tests use `createComposeRule()`. New sources/engines subclass the contract kits in `core-source-testkit`.

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
_TechEmpower — Technology for All. Access Made Easy._ (links)
*What's new* (feature bullets with emoji from docs/slack-release-template.md palette)
*Under the hood* (infra/test bullets)
*Install* (APK link, release link, compare link)
_Installed on ... · clean launch._
:hammer_and_wrench:  realm `fantasy`  ·  built `$time`  ·  commit `$hash`
```
Sigil: `python3 -c "..."` with realm-sigil word lists from `~/Projects/realm-sigil/words/realms.json`.
