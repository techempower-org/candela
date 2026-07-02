# Adding a voice engine to Candela

A **voice engine** turns chapter text into PCM that Candela's playback
pipeline streams. Adding one is an afternoon's work against three small
contracts — `VoiceEnginePlugin` (identity, synth, catalog), `ModelSpec`
(what you need on disk), and optionally `StreamingSynth` (parallel
secondary instances) — with **zero central edits**: no `when` arm, no DI
module, no registry entry, no `EnginePlayer` change.

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
scaffolded `handles()` returns `false`. Dispatch reaches you via
`VoiceEngineRegistry.byKey(EngineKey("voice_<id>"))` — the stable
discriminator — never via the legacy sealed `when`.

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
another speaker by a concurrent render.

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

## 4. Catalog + family card

- `catalogEntries()` — your static voice roster (unique, non-blank ids).
  Engines with runtime-discovered rosters (like Azure / System TTS)
  return `emptyList()` and project rows through their roster path.
- `familyDescriptor()` — the Plugin Manager card: id **must equal**
  `engineId`, plus display name, description, source URL, license, size
  hint. Ship `defaultEnabled = false` until the engine is proven.

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
  `StreamingDispatch.swapStepOrder()` — you never destroy your own pool.

`EnginePlayer` consumes the capability generically
(`registry.byKey(key) as? StreamingSynth`) — implementing it requires
no EnginePlayer edit. Azure's lookahead fan-out is deliberately NOT a
`StreamingSynth` (no native lifecycle); don't force call-fan-out engines
through this interface.

## 6. Turn the contract test green — and keep it green

```bash
./gradlew :core-playback:testDebugUnitTest --tests "*<Name>EnginePluginContractTest*"
```

The kit checks metadata + coherence (JVM-safe by design — native synth
can't run in unit tests): `voice_*` engineId, sample keys belong to your
family, catalog ids unique, `handles()` agrees with key round-trips,
descriptor id == engineId, and `supportsExport=false` ⇒
`generateAudioPCM` returns the documented `null`.

**`supportsExport` semantics matter**: `false` means the offline
audiobook-export path rejects your engine with friendly copy, and the
background pre-render worker treats a slip-through as load-failure
(retry with backoff — see the gate in `ChapterRenderJob`). Only claim
`true` when you synchronously render real PCM.

## PR checklist

- [ ] Contract test green; full `:core-playback:testDebugUnitTest` green.
- [ ] CI **Build APK** green — proves the KSP-generated Hilt binding
      resolves in the `:app` graph (the only real proof; module compile
      can't see the whole graph).
- [ ] No central edits: no `EngineType` variant, no hand DI module, no
      registry/EnginePlayer/`VoiceFamilyIds` change. (Your literal
      `"voice_<id>"` is the identity; a `VoiceFamilyIds` constant is
      optional polish for in-tree call sites, not a requirement.)
- [ ] `EngineMutex` discipline documented risks reviewed (§2).
- [ ] On-device QA: play a long chapter; if you implemented
      `StreamingSynth`, also parallel-synth ON + mid-play voice swap
      (watch the #1383 teardown breadcrumbs in logcat).

Reviewers look for exactly those five things.
