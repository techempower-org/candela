# Voice Notes — design spec (v2)

**Status:** DRAFT v2.1 — hardened via adversarial review + verify pass (awaiting JP review) · **Date:** 2026-07-06 · **Tracking:** #1657
**Inputs:** morpheus feasibility + recording/storage audit · nebula transcription-model research + adversarial spec review + v2 verification.

A Plaud-device-style capability inside Candela: **record audio → transcribe on-device → AI-summarize → save as a searchable note**, plus first-class **manual note-taking**. Core promise: **audio and transcripts never leave the phone**; text leaves only on explicit per-action consent.

> **v2 changelog:** closed a real auto-backup privacy leak (§3.7 — was absent in v1); made long-recording transcription durable (§3.2, was a bare coroutine); expanded the data model with status/language/duration + transcript-vs-body ownership (§3.4); deferred the sync layer to Later, keeping at-rest/backup privacy in MVP (§7); fixed the download-reuse citation to `AsrModelProvider`.
>
> **v2.1 (verify-pass fixes):** notes now live in a **separate `notes.db`** — Android backup excludes per *file*, not per *table*, so a shared DB could not be selectively excluded without killing library/settings backup (§3.4/§3.7 were mutually incompatible as written). Also aligned the audio path (`filesDir/recordings/`) across §3.1/§3.7/§6 — a backup-exclude path that doesn't match the write path silently does nothing.

---

## 1. Goals / Non-goals

**Goals (MVP)**
- Record voice (memos and long meetings/lectures) to a local file; play back with scrub.
- Transcribe a recording **on-device** to timestamped, punctuated text — **durably**, surviving backgrounding for hour-long inputs.
- Optionally summarize the transcript (title + key points / action items) via the existing LLM layer, **only with explicit consent**.
- Store notes locally (audio + transcript + summary + editable body + tags); search them.
- Manual/typed notes with no recording are first-class.
- **Privacy holds by construction**: nothing auto-syncs, auto-backs-up, or logs note content.

**Non-goals (v1 — revisit later)**
- Speaker diarization; real-time live transcription during recording; cloud audio upload/transcription.
- **Cloud text sync** (the `NotesSyncer`) — deferred to Later (§7); MVP is device-local only.
- At-rest DB/audio encryption (strict-privacy mode) — Later/optional (§3.7, open Q6).

---

## 2. Data / processing model (Model **B**)

| Stage | Where it runs | Leaves device? |
|---|---|---|
| Record | Device (`MediaRecorder`) | No — app-private file, **backup-excluded** |
| Transcribe | Device (sherpa `OfflineRecognizer`, durable) | No |
| Summarize | Cloud LLM (or LAN Ollama) | **Only transcript text, on explicit consent** |
| Store | Room + app-private files | No — **backup-excluded**, never logged |
| Sync (Later) | InstantDB via a future syncer | Deferred; text-only + encrypted when built |

**Why B**: on-device transcription is achievable with **no dependency bump** (see §3.2); there is no real on-device summarizer (only LAN-Ollama), so "fully on-device" can't summarize well; uploading audio contradicts the privacy promise and is unnecessary.

---

## 3. Architecture — layers & components

### 3.1 Capture — `feature/.../notes/record/`
- **`AudioRecorder`** — wraps `MediaRecorder` (AAC/`.m4a`, mono, ~64–96 kbps) → `filesDir/recordings/<noteId>.m4a`. Start/pause/resume/stop → path + duration.
- **`RecordingService`** — a **`microphone`-typed foreground service** (Android 14+ requires the typed FGS + `FOREGROUND_SERVICE_MICROPHONE` permission; `RECORD_AUDIO` already declared). Model it on the existing playback service (don't subclass — that one is Media3-specific). Reuse `AudioFocusController` as a pattern (it's playback-tuned).
- **`RecordingController`** (VM) — timer, amplitude/waveform, permission gate. **Must survive process death mid-record**: finalize the `.m4a` on stop/pause; on crash, a truncated file is recoverable or discarded, never a half-written DB row.

### 3.2 Transcription — `core-playback/.../transcribe/offline/` (new sub-package)
- **`OfflineTranscriber`** — loads a sherpa-onnx `OfflineRecognizer` (Whisper-class, punctuated) and decodes an audio file → `Transcript` (segments `startMs/endMs/text`). **javap-confirmed: `OfflineRecognizer` + Whisper configs already ship in the resolved sherpa AAR — no dependency bump, this is wiring.**
- **Durability (v2 fix):** transcription runs in a **`WorkManager` job backed by an FGS** (not a bare `Dispatchers.Default` coroutine — that dies on backgrounding/OOM and a 1 h meeting would silently never finish). **Chunk** long audio and stream segments to the store as they complete, with bounded memory; the job is resumable and cancellable, and reports progress.
- **`TranscriptionModelProvider`** — the model is a **downloadable asset** reusing the existing **`AsrModelProvider`** download-to-disk + load-by-path path (not the TTS `ModelSpec` path). Default **Whisper base int8** (~200 MB, EN+ES, punctuation/casing, MIT); **tiny** (~100 MB) as a light option. *(Confirm exact int8 sizes before setting a storage budget.)*

### 3.3 Summarization — `core-llm` (reuse) + thin use-case
- **`SummarizeTranscriptUseCase`** — prompt (title + key points + action items) → the existing provider-agnostic `LlmProvider.stream()` (mirrors `ChapterRecap`). Summary language follows the transcript language (EN/ES).
- **Consent gate:** invoked only on explicit "Summarize" tap (or a global auto-summarize opt-in) AND a provider configured. No provider/consent → the note keeps its transcript. This is the *only* path that sends note text off-device.

### 3.4 Storage & data model — `core-data` (separate `NotesDatabase`)
- **Separate database (v2.1):** notes live in a **new `NotesDatabase` (`notes.db`)**, *not* tables in `StoryvoxDatabase`. Rationale: Android backup excludes per **DB file**, not per table (§3.7) — a shared DB can't be selectively excluded without also killing library/settings backup. A dedicated file is cleanly excludable, isolates note data, and keeps the feature **off `StoryvoxDatabase`'s migration path** (no v20 bump). It exports its own schema (own ubox0 KSP run).
- **`NoteEntity`** mirrors the proven `TeleprompterScript` (v18) entity pattern: UUID PK, no FK, comma-separated `tags` + `LIKE` search. One table serves both typed notes and recordings via nullable fields:
  - `id`, `title`, `createdAt`, `updatedAt`, `tags`
  - `audioPath: String?`, `durationMs: Long?`
  - `transcript: String?` (**immutable** — the source-of-truth ASR output; re-transcribe replaces it wholesale), `transcriptLang: String?`
  - `summary: String?`
  - `body: String?` (**editable** — user's typed/edited content; independent of transcript)
  - `transcriptionStatus: enum { NONE, PENDING, RUNNING, FAILED, DONE }`
- **`NotesRepository`** — CRUD + `LIKE` search over title/body/transcript (FTS4 = Later). **Deleting a note deletes its audio file** (no dangling paths); startup/periodic sweep reclaims **orphaned audio** (files with no row — e.g. crash mid-create). DAO mirrors `TeleprompterScriptDao`.
- `NotesDatabase` ships at its own v1 (fresh DB, no migration of existing data); schema JSON exported via the ubox0 KSP workflow proven in #1640.

### 3.5 Sync — **deferred to Later**
The opt-in `@IntoSet NotesSyncer` (text-only, encrypted, off by default) is **out of MVP** (resolves the v1 §7↔Q5 contradiction). When built: never audio (sync carries small JSON only); encrypt note bodies client-side (Tink AES-GCM, like Azure creds) since InstantDB stores plaintext.

### 3.6 UI — `feature/.../notes/`
- **Notes list** — cards (title, snippet, date, mic/pencil glyph, duration, a transcription-status chip). Search. Primary **Record** FAB + "New note" (typed).
- **Record screen** — waveform, timer, pause/stop → create note + enqueue transcription.
- **Note detail** — audio player (play/scrub, tap a transcript segment to seek), transcript (read-only), summary (with "Summarize" if pending), editable body, tags, delete/export. `core-ui` theme, dark/light.
- Entry point + name: open (Q1/Q2).

### 3.7 Privacy at rest & backup — **(v2, the critical fix)**
The core promise ("never leaves the phone") is **false unless** we fix Android backup, because the app currently ships `allowBackup="true"` with *include-everything-except* rules (`data_extraction_rules.xml` / `backup_rules.xml` exclude only `secrets`/`wallet/`/`deadline_reminders.json`). Without action, `notes/audio/` **and** the plaintext notes DB would auto-upload to Google cloud-backup **and** device-transfer by default.

**MVP requirements:**
1. **Exclude from backup + device-transfer** — add the **`recordings/`** audio dir (must match §3.1's write path exactly — a mismatched exclude path silently does nothing) **and the `notes.db` file** (cleanly excludable *because* it's a separate DB, §3.4) to the exclude rules (the known #951/#1514 pattern). Verify both `cloud-backup` and `device-transfer` domains.
2. **Never log note content** — `Log.w/e` survive release builds (per project rule); audio paths ok, transcript/summary/body never.
3. **App-private storage only** — `filesDir` (not external, not `cacheDir` which is evictable).

**Open (Q6):** optional **at-rest encryption** for a strict-privacy mode — `EncryptedFile` for audio (as `wallet/` does) + SQLCipher for the DB. Heavier; propose as Later unless JP wants it in MVP.

---

## 4. Data flow & failure handling

```
[Record FAB] → RecordingService(FGS mic) + AudioRecorder → filesDir/recordings/<id>.m4a
[Stop] → NotesRepository.create(Note{audioPath, durationMs, status=PENDING})
  → WorkManager transcription job (FGS, chunked) → status RUNNING → segments stream in → status DONE
[Summarize tap / auto-opt-in] ── consent + provider ──> SummarizeTranscriptUseCase → LlmProvider.stream() → note.summary
```

**Failure/edge paths (all explicit):**
- Mic permission denied → prompt, graceful abort, no row.
- Model missing → offer download, keep audio, status stays PENDING.
- Transcription fails/cancelled → status FAILED, audio + manual body retained, retry available.
- App killed mid-record → finalize or discard the `.m4a`; never a dangling row/path (orphan sweep).
- Storage full → fail the record/transcribe cleanly with a message; don't corrupt existing notes.
- Recording vs. active TTS playback → audio-focus arbitration (pause playback while recording).
- LLM no-provider / mid-stream failure → keep transcript, surface a non-fatal error.

---

## 5. Migration & permissions
- New **`NotesDatabase`** (`notes.db`) at its own v1 — separate Room DB + schema export; **no** `StoryvoxDatabase` version bump (keeps notes file-excludable from backup per §3.7 and off the main DB's migration path).
- New: `FOREGROUND_SERVICE_MICROPHONE` (+ `foregroundServiceType="microphone"` on the service). `RECORD_AUDIO` already present.
- Backup exclude-rule edits (§3.7) — coordinate with the existing rules files (serialization point).
- New optional transcription-model download (no APK size impact).

## 6. Testing
- `AudioRecorder` — start/stop yields a non-empty `.m4a` of expected duration (Robolectric/instrumented).
- `OfflineTranscriber` — decode a short fixture → expected segments (skip if model absent in CI).
- Transcription **WorkManager** job — chunking + resume + cancel + FAILED transitions (WorkManager test harness).
- `SummarizeTranscriptUseCase` — fake `LlmProvider` (hand-rolled, `= Unit` defaults) → prompt shape + consent gate.
- `NotesRepository` — in-memory Room; CRUD + search + **audio deleted with note** + orphan sweep.
- **Backup rules** — assert the `recordings/` dir + `notes.db` file are excluded in both backup domains (guards the core promise).
- Compose UI tests for list/record/detail.

## 7. MVP scope vs. Later
**MVP:** record → durable on-device transcribe → optional consented summary → local note (edit/search); manual notes; delete/export; **§3.7 backup/at-rest/no-log privacy**. Sync **not built**.
**Later:** opt-in encrypted text sync (`NotesSyncer`) · at-rest encryption (Q6) · diarization · live transcription · FTS4 · quick-capture tile · share-to-Candela import · Wear capture.

## 8. Open questions for JP
- **Q1 Entry point** — dedicated **Notes** nav destination *(default)*, or a Library tab?
- **Q2 Name** — "Notes" / "Memos" / "Voice Notes" / on-brand candle motif? *(default: "Notes")*
- **Q3 Auto-summarize** — off, tap-to-summarize *(default, most private)*, or a global auto-summarize opt-in?
- **Q4 Transcription model** — ✅ *resolved by research:* Whisper base int8 default, tiny as light option (only EN+ES+punctuation option in-stack). Confirm if you want a different language bar than EN+ES.
- **Q5 Sync in MVP** — ✅ *resolved:* deferred to Later (nebula concurs; keeps MVP device-local + resolves the scope contradiction).
- **Q6 At-rest encryption** — strict-privacy `EncryptedFile`+SQLCipher: MVP or Later? *(default: Later; backup-exclusion already closes the off-device leak)*
