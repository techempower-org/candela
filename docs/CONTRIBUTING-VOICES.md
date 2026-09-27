# Adding a voice engine to Candela

A **voice engine** turns chapter text into PCM that Candela's playback
pipeline streams. Adding one is an afternoon's work against three small
contracts — `VoiceEnginePlugin` (identity, synth, catalog), `ModelSpec`
(what you need on disk), and optionally `StreamingSynth` (parallel
secondary instances). The DI side is genuinely zero-edit: `@VoicePlugin`
generates your Hilt binding — no module, no registry entry.

**No central edits (#1500/#1501).** Catalog rows (`CatalogEntry`,
`UiVoiceInfo`) are typed on the de-sealed `EngineKey`; the sealed
`EngineType` is only a typed *view* of it — the six built-in families
map to their variants, every other engine id to `EngineType.Plugin(key)`.
Every dispatch site (EnginePlayer load/pool/synth/sample-rate, recap,
export, pre-render, the Voice Library) has ONE generic `Plugin` arm that
drives your engine purely through the contracts below — see §7 for
exactly what that path does. A new engine is the plugin class and its
contract test; nothing else.

Reference engine (living documentation): **`KittenEnginePlugin`** — the
smallest real one, exercising every contract including `StreamingSynth`.

## 1. Scaffold

```bash
scripts/new-voice-engine.sh <id> "<Display Name>"
# e.g.
scripts/new-voice-engine.sh whisper "Whisper TTS"
```

This generates, inside `:core-playback` (engines are in-module by design):

```
voice/engines/<Name>EnginePlugin.kt              # @VoicePlugin("voice_<id>"), all members stubbed
test/.../engines/<Name>EnginePluginContractTest.kt   # wired to the shared kit, green from run 1
```

**That's the whole wiring.** `@VoicePlugin` makes the `:core-plugin-ksp`
processor emit your Hilt `@Binds @IntoMap @StringKey` module;
`VoiceEngineRegistry` discovers it from the multibinding and fails fast
at graph build if your bound key and `engineId` ever disagree. CI's
**Build APK** is the proof.

Your engine is **de-sealed**: it has no `EngineType` variant and the
scaffolded `handles()` returns `false`. Every `type` you receive is an
`EngineType.Plugin`, and `VoiceEngineRegistry.forType` routes it to you
by key (`byKey(EngineKey("voice_<id>"))`) — `handles()` is only for the
built-in families.

## 2. Implement synthesis

Fill in `generateAudioPCM(type, text, speed, pitch)`: one sentence in,
one 16-bit mono LE PCM buffer at `sampleRate` out, `null` on failure.

**The `EngineMutex` rule is law** (from the `VoiceEnginePlugin` contract
kdoc, verbatim):

> Callers MUST hold `EngineMutex.mutex` across [type]-matched
> loadModel + this call (see `AudiobookSynthesizer` / `ChapterRenderJob`).

You don't take the lock yourself — the call sites do — but your engine
must tolerate load/synth being serialized against every other engine's,
and must never synthesize concurrently with its own `loadModel` (native
engines SIGSEGV on that; see the `EngineMutex` kdoc for the incident
history).

Shared-model engines (one loaded model, N speakers): re-assert your
active speaker from `type` at the top of every `generateAudioPCM` — the
#1263-correct pattern; the process-wide singleton may have been left on
another speaker by a concurrent render. Read it (and any per-voice
params) off the key: `(type as? EngineType.Plugin)?.key?.speakerId` /
`?.key?.params`.

## 3. Model loading — `ModelSpec` + `loadModel`

If your engine loads files from disk, override:

- `modelSpec(type, voiceId)` — build a `ModelSpec` describing what you
  need (`OnnxWithTokens`, `OnnxTokensVoices`, `SharedDir` — speaker id
  rides in the spec for shared-model engines — or a new variant if your
  shape is genuinely new). Voice directories come from the injected
  `VoiceManager` (`dagger.Lazy` — see how the scaffold's siblings inject
  it).
- `loadModel(spec)` — perform the native load; return `"Success"` or an
  error string. Idempotent re-loads should be cheap.

Cloud/framework engines keep the defaults (`ModelSpec.None` /
`"Success"`).

**Install state + downloads.** `isVoiceReady(type, voiceId)` decides
whether a voice lists as *Installed* in the Voice Library; the default
checks that every file your `modelSpec` names exists (so `ModelSpec.None`
reads ready). If your files come from the network, override
`modelDownloads(type, voiceId)` with `ModelDownload(url, target, sizeBytes)`
entries — `VoiceManager.download` fetches them in order (with progress)
and then re-checks `isVoiceReady`. Empty means "nothing to fetch".

## 4. Catalog + family card

- `catalogEntries()` — your static voice roster (unique, non-blank ids).
  Build each row with `engineKey = EngineKey("voice_<id>", speakerId, params)`
  (the primary constructor); `VoiceManager` merges de-sealed engines'
  rows into the library automatically. Engines with runtime-discovered
  rosters (like Azure / System TTS) return `emptyList()` and project rows
  through their roster path.
- `familyDescriptor()` — the Plugin Manager card: id **must equal**
  `engineId`, plus display name, description, source URL, license, size
  hint. Ship `defaultEnabled = false` until the engine is proven. It is
  appended to the Plugin Manager automatically.
- `familyDescriptor().presentation` (optional) — how the Voice Library
  renders your section and rows: `shortLabel` (row subtitle),
  `sectionLabel`, `searchTerm`, `displayOrder`, `tierOrder`, and colour /
  icon **tokens** (`VoiceAccent`, `VoiceIcon`). The defaults derive from
  the descriptor (label = `displayName`, local engines sort after the
  built-in local families and before cloud), so you only set what you
  want to change. `collapseToken` is an on-disk key — never change it
  once shipped.

## 5. Parallel synth (optional) — `StreamingSynth`

If your engine can run multiple native instances, implement
`StreamingSynth`: `acquirePool(spec, size, threadsPerInstance, tuning)`
returns model-loaded, speaker-pinned `Handle`s. Rules the contract kdoc
pins (read it before implementing):

- **Cap-on-failure**: first failed secondary load → destroy it, return
  the achieved prefix. A short pool is normal, not an error.
- **Speaker bakes at construction** — `Handle.generatePCM` has no
  per-call key, deliberately.
- Callers own the handles and drive teardown through
  the `SwapStep` order `StreamingPoolLifecycle.swapTo` runs — you never destroy your own pool.

Implementing `StreamingSynth` is all it takes: the generic load path
builds your pool with the user's parallel-synth setting and the pipeline
adapts your handles (see §7). Azure's lookahead fan-out is deliberately
NOT a `StreamingSynth` (no native lifecycle); don't force call-fan-out
engines through this interface.

## 6. Turn the contract test green — and keep it green

```bash
./gradlew :core-playback:testDebugUnitTest --tests "*<Name>EnginePluginContractTest*"
```

The kit checks metadata + coherence (JVM-safe by design — native synth
can't run in unit tests): `voice_*` engineId, sample keys belong to your
family, every sample key **dispatches to your plugin through the
registry** (the production path), catalog ids unique and keyed to your
engine, descriptor id == engineId, and `supportsExport=false` ⇒
`generateAudioPCM` returns the documented `null`.

**Two capability flags**: `supportsExport = false` means the offline
audiobook-export path rejects your engine with friendly copy. The
background pre-render worker asks `supportsBackgroundRender` instead
(defaults to `supportsExport`; override it when the answers differ) and
cleanly skips an engine that says `false` — no retry loop. Only claim
`true` for either when you synchronously render real PCM.

### Contributor gotcha: unit tests need Java 21

`core-playback`'s Robolectric tests sandbox Android SDK 36, which
**requires Java 21** — under JDK 17 they fail at class init with
`UnsupportedOperationException ... requires Java 21 (have Java 17)`.
Point `JAVA_HOME` at a JDK 21 before running
`:core-playback:testDebugUnitTest`. (CI and the shared runner are
already configured; this bites local setups.)

## 7. How your engine is reached (no central edits)

What the generic `EngineType.Plugin` path does, so you know what you're
relying on (all of it in place since #1500/#1501):

1. **Library** — `VoiceManager` merges your `catalogEntries()` and asks
   `isVoiceReady` for install state; `download` runs your
   `modelDownloads`. The Voice Library groups, labels, colours, orders
   and searches your rows from your descriptor's `presentation`; the
   Plugin Manager shows your family card.
2. **Playback load** — `EnginePlayer` frees any other family's pool, then
   `loadModel(modelSpec(type, voiceId))` under `EngineMutex`; if you
   implement `StreamingSynth` it rebuilds your pool (tagged with your
   `engineId`, same #1383/#1386 teardown order as the built-ins).
3. **Synthesis** — the serial producer calls your `generateAudioPCM(type, …)`
   under `EngineMutex`; pooled secondaries call your `Handle.generatePCM`.
   `sampleRate` configures the AudioTrack — keep it lock-free.
4. **Recap, export, pre-render** — recap loads your primary only; offline
   export and background pre-render gate on `supportsExport` and dispatch
   through the registry like every other engine.

## PR checklist

- [ ] Contract test green; full `:core-playback:testDebugUnitTest` green.
- [ ] CI **Build APK** green — proves the KSP-generated Hilt binding
      resolves in the `:app` graph (the only real proof; module compile
      can't see the whole graph).
- [ ] No hand DI module, no registry change, no edit outside your plugin
      class + its test. (Your literal `"voice_<id>"` is the identity; a
      `VoiceFamilyIds` constant is optional polish for in-tree call
      sites, not a requirement.)
- [ ] `EngineMutex` discipline documented risks reviewed (§2).
- [ ] On-device QA: play a long chapter; if you implemented
      `StreamingSynth`, also parallel-synth ON + mid-play voice swap
      (watch the #1383 teardown breadcrumbs in logcat).

Reviewers look for exactly those five things.
