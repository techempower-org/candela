# Plugin DX — "anyone can write a backend or a voice engine"

*Design spec · 2026-07-01 · approved direction: in-tree contributor plugins,
full voice inversion incl. streaming, delivered as one epic branch.*

## Problem

Candela's two extension points are unevenly pluggable:

- **Sources** are close: `@SourcePlugin` + KSP (#384 → #1371 → #1481) generate
  all DI. But a contributor still hand-edits `SourceIds`, must know the
  `Dispatchers.IO` pin (#585) and other tribal gotchas by folklore, and gets no
  scaffold or guide. "~4 touchpoints" is true only if you already know the four.
- **Voice engines** have a half-seam (#1372): `VoiceEnginePlugin` exists and six
  engines implement it, but bindings are hand-written in
  `VoiceEnginePluginModule`, model loading lives in `when` arms in
  `AudiobookSynthesizer`/`ChapterRenderJob`, `EngineType` is a sealed hierarchy
  you must extend, and `EnginePlayer`'s parallel-synth streaming path
  special-cases Kokoro (`secondaryKokoroEngines`, `EngineStreamingSource`).

A motivated outside contributor cannot add either kind of plugin without
reading tribal memory. This epic makes both contracts self-teaching:
scaffold → implement one unit → run the contract kit → PR.

## Delivery model (decided)

**In-tree contributor PRs.** Plugins are modules/classes inside the GPL-3.0
repo, shipped in the APK. No runtime dex loading, no extension APKs — that is
explicitly out of scope and nothing here should preclude it later.

## Success criteria (measurable)

| | New source | New voice engine |
|---|---|---|
| Units created | 1 module `source-<id>/` | 1 class in `core-playback/voice/engines/` |
| Central edits | 2 one-liners (`settings.gradle.kts` include, `:app` dep) | **0** |
| Forbidden edits | no `SourceIds`, no DI modules, no enum/sealed edits | no DI module, no `when` arm, no sealed-class edit, no EnginePlayer edit |
| Verification | run `FictionSourceContractTest` subclass locally | run `VoiceEnginePluginContractTest` subclass locally |
| Time following guide | ≤ 30 min to first green contract test | ≤ an afternoon incl. model wiring |

## Track A — sources

1. **Scaffold** `scripts/new-source.sh <id> "<Display Name>"`:
   generates `source-<id>/` containing `build.gradle.kts` (copied from the
   canonical minimal source), `<Name>Source.kt` skeleton — `@SourcePlugin`
   annotated, `FictionSource` methods stubbed with honest
   `FictionResult.NotFound`/`AuthRequired` defaults, `net/<Name>Api.kt` stub
   with the `withContext(Dispatchers.IO)` pin already written, and
   `src/test/.../<Name>SourceContractTest.kt` pre-wired to the kit. Prints the
   two one-line edits to finish.
2. **Contract-test kit**: abstract `FictionSourceContractTest` (new tiny module
   `core-source-testkit`, test-fixtures style) encoding the gotchas as checks:
   - all network entry points dispatch off the caller thread (IO pin, #585)
   - 401/403 → `AuthRequired`, 429 → `RateLimited`, never raw exceptions
   - paging: `hasNext=false` terminates; page 1 idempotent
   - blank-query `search` doesn't crash; empty results are `Success(empty)`,
     not errors
   - Cloudflare-challenge body → detected, never stored as chapter text
   Sources provide fixtures via a small `SourceFixtures` hook (mock OkHttp).
3. **Retire `SourceIds` as a touchpoint**: annotation `id` is the source of
   truth; existing constants remain as aliases for in-tree call sites; the
   guide and scaffold never mention it.
4. **Guide** `docs/CONTRIBUTING-SOURCES.md`: the 30-minute walkthrough —
   scaffold → fill in API → fixtures → contract test green → PR checklist
   (CI is the compile gate; what reviewers look for). Names a *reference
   source* (`source-hackernews`) as living documentation.

## Track B — voice engines (full inversion)

1. **Contract v2** — `VoiceEnginePlugin` gains:
   - `suspend fun loadModel(spec: ModelSpec)` — `ModelSpec` is a sealed data
     description (OnnxWithTokens / OnnxTokensVoices / SharedDir / None) so the
     divergent load signatures become data; plugins get `VoiceManager` +
     `@ApplicationContext` injected. The load `when` arms in
     `AudiobookSynthesizer` / `ChapterRenderJob` are deleted.
   - **Streaming capability**: `interface StreamingSynth` (optional, engines
     opt in): pool-handle acquisition, per-chunk PCM synthesis, teardown.
     `EnginePlayer` consumes `plugin as? StreamingSynth` generically; the
     Kokoro-only `secondaryKokoroEngines` path becomes "any StreamingSynth".
2. **De-seal `EngineType`** → stable value type
   `EngineKey(engineId: String, speakerId: Int? = null)`. Migration shims
   (typealiases + factory fns) keep call sites compiling mid-epic; shims are
   deleted before the epic merges.
3. **KSP bindings**: `@VoicePlugin` annotation processed by the existing
   `core-plugin-ksp` (same pattern #1481 proved); hand-written
   `VoiceEnginePluginModule` deleted. New engine = annotated class, zero DI.
4. **Engine contract kit**: abstract `VoiceEnginePluginContractTest`:
   - `catalogEntries()` ids unique, family id == engineId, descriptor valid
   - `generateAudioPCM` returns non-empty 16-bit mono at `sampleRate` for a
     smoke text (model-backed engines run under a fake/tiny model fixture;
     cloud/system engines assert the documented `null`)
   - speaker re-assertion on shared-model engines (#1263 regression)
   - `supportsExport` consistent with synchronous-render capability
   - `EngineMutex` discipline: synth without the lock in tests is flagged
   (kit lives beside the source kit: `core-source-testkit` hosts both abstract
   classes, or splits into `core-voice-testkit` if the dependency graph forces
   it — implementation plan decides, contract stays the same)
5. **Guide** `docs/CONTRIBUTING-VOICES.md` + `scripts/new-voice-engine.sh <id>`
   generating the annotated plugin class + contract-test subclass. Reference
   engine: `KittenEnginePlugin` (smallest real one).

## Epic mechanics & risk containment

- Branch **`epic/plugin-dx`** off main; one final squash-merge (or a short
  stack if review demands). Internal commit order, each CI-green when pushed:
  **A1** scaffold+kit → **A2** SourceIds retirement + guide → **B1** contract
  v2 + KSP + EngineKey shims → **B2** EnginePlayer streaming inversion →
  **B3** engine kit + scaffold + guide + shim removal.
- **Before B2 touches EnginePlayer**: extract the streaming-dispatch decisions
  into pure `internal fun`s with JVM tests (the established EnginePlayer
  testing pattern) in a preparatory commit; invert only after those tests pin
  behavior.
- **Daily rebase** of the epic onto main; **playback freeze** — no other agent
  wave touches `core-playback` while the epic is open.
- Rollback story: tracks are independent; if B2 proves unshippable the epic
  can land A + B1 + B3-minus-streaming and B2 becomes a follow-up — the
  contract kit and docs simply document the Kokoro special case until then.

## Testing strategy

The contract kits are the product *and* the proof: inside the epic, all six
existing engines and four diverse sources (hackernews=API, ao3=scrape+CF,
radio=streaming, epub-import=local) are retrofitted onto the kits. A kit that
all ten pass without weakening its assertions is evidence the contracts are
real. EnginePlayer changes additionally ride the extracted pure-fn tests and
the CI Build APK gate (Hilt whole-graph proof, as #1481 demonstrated).

## Out of scope

Runtime-loadable extension APKs · new sources/engines themselves · Play-store
listing copy · `BrowseSourceUi` descriptor work (#1482, separate) · EnginePlayer
refactors beyond the streaming dispatch seam.

## Risks

| Risk | Mitigation |
|---|---|
| B2 destabilizes playback | pure-fn extraction first; CI-green milestones; rollback split (land A+B1+B3) |
| Epic rots against main | daily rebase; playback freeze window; hot-file merge priority |
| Kits too weak (rubber-stamp) or too strict (flaky) | retrofit-10 exercise inside the epic calibrates them before docs advertise them |
| KSP processor churn breaks sources | processor changes are additive (new annotation), #1481's generated-path tests stay green |

## Effort

A: ~1 agent-day. B1: ~1–2. B2: ~2–3 (dominated by test extraction). B3: ~1.
Epic wall-clock: roughly a week of dreamteam waves under the freeze window.
