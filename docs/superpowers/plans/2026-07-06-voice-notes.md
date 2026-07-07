# Voice Notes — Implementation Plan

> **For agentic workers:** This is a **multi-agent** plan. Each phase's component is owned by a dreamteam agent that implements it TDD-style (the dreamteam analog of `superpowers:subagent-driven-development`). Steps are **component contracts** (interface + files + tests + acceptance), not line-by-line code, because each executor is a context-rich agent that produces its own bite-sized TDD within its slice. Checkbox tracking per phase. Merge order = phase order.

**Goal:** Ship the Voice Notes MVP (epic #1657): record → on-device transcribe → optional consented summary → local searchable note; first-class manual notes; privacy by construction.

**Architecture:** Model B (spec-v2.1 §2). Separate `NotesDatabase` (backup-excludable); on-device sherpa `OfflineRecognizer` (already in AAR); durable WorkManager transcription; reuse `LlmProvider.stream()` to summarize; new `feature/.../notes/` Compose surface. Audio device-local, never synced, never logged.

**Tech stack:** Kotlin · Room (separate DB) · Hilt · WorkManager · MediaRecorder + `microphone`-typed FGS · sherpa-onnx OfflineRecognizer · Compose · DataStore.
**Spec:** `docs/superpowers/specs/2026-07-06-voice-notes-design.md` (v2.1). **Locked product Qs:** Notes nav destination · name "Notes" · auto-summarize off/tap · at-rest encryption Later.

---

## Dependency graph & merge order

```
Phase 1: Storage + backup rules ──┬──────────────┐         [BLOCKS ALL]
                                  ▼              ▼
Phase 2a: Recording        Phase 2b: Transcription          [parallel, need P1]
                                  └──────┬───────┘
                                         ▼
Phase 3: Summarization  (needs a transcript)                [needs P2b]
Phase 4: Notes UI       (needs P1; stubs P2/P3 flows)       [needs P1]
```

Phase 1 lands and merges first. Phases 2a/2b parallelize. Phase 4 (UI) can start against P1 with stubbed transcription/summary and integrate as they land. Each phase is its own PR; **hold merges until each CI is green on both gates + reviewed** (no eager-merge; per this session's lesson).

---

## Phase 1 — Storage & privacy foundation — owner **morpheus** — BLOCKS ALL
**Files:** create `core-data/.../notes/{NotesDatabase,NoteEntity,NoteDao,NotesRepository}.kt`; DI `@Provides NotesDatabase`; `app/src/main/res/xml/data_extraction_rules.xml` + `backup_rules.xml` (add excludes).
**Contract:**
- `NoteEntity`: `id`(UUID PK), `title`, `createdAt`, `updatedAt`, `tags`(comma-sep), `audioPath:String?`, `durationMs:Long?`, `transcript:String?`(immutable), `transcriptLang:String?`, `summary:String?`, `body:String?`(editable), `transcriptionStatus`(NONE/PENDING/RUNNING/FAILED/DONE). Mirror `TeleprompterScript` (v18) entity idioms.
- `NotesDatabase`: **separate** Room DB `notes.db`, own **v1**, `exportSchema=true` (JSON via ubox0 KSP). **Not** a `StoryvoxDatabase` bump.
- `NoteDao`: CRUD + `LIKE` search over title/body/transcript, `Flow` reads.
- `NotesRepository`: CRUD + search; **deleting a note deletes its audio file**; startup orphan-audio sweep (files with no row).
- **Backup (§3.7):** exclude the `notes.db` file **and** `filesDir/recordings/` in **both** `cloud-backup` and `device-transfer` domains (#951/#1514 pattern). Path must exactly match Phase-2a's write path.
**Tests:** in-memory Room CRUD+search; delete-note-deletes-audio; orphan sweep; **backup-rules test asserts both paths excluded in both domains** (guards the core promise).
**Acceptance:** module compiles at `:app`; `notes.db` v1 schema JSON committed (ubox0 KSP); all tests + CI green.

## Phase 2a — Recording — owner **morpheus-1619** (reuse) — needs P1
**Files:** create `feature/.../notes/record/{AudioRecorder,RecordingService,RecordingController}.kt`; `AndroidManifest` (`FOREGROUND_SERVICE_MICROPHONE` perm + `foregroundServiceType="microphone"`).
**Contract:**
- `AudioRecorder`: wrap `MediaRecorder` → AAC `.m4a` mono ~64–96 kbps → `filesDir/recordings/<noteId>.m4a`; start/pause/resume/stop → (path, durationMs). Finalize file on stop; on process-death leave a recoverable-or-discarded file, never a half-written row.
- `RecordingService`: **`microphone`-typed FGS** (model on the playback service, don't subclass — it's Media3-specific). Reuse `AudioFocusController` pattern; pause active TTS while recording.
- `RecordingController` (VM): timer, amplitude, `RECORD_AUDIO` permission gate → on stop create `NoteEntity{audioPath,durationMs,status=PENDING}`.
**Tests:** start/stop yields non-empty `.m4a` of ~expected duration (Robolectric/instrumented); permission-denied path aborts cleanly.
**Acceptance:** record→file→Note row created; FGS shows a notification; CI green.

## Phase 2b — Transcription — owner **morpheus** — needs P1
**Files:** create `core-playback/.../transcribe/offline/{OfflineTranscriber,TranscriptionModelProvider}.kt`; `feature/.../notes/TranscriptionWorker.kt` (WorkManager).
**Contract:**
- `OfflineTranscriber`: load sherpa `OfflineRecognizer` (Whisper base int8; javap-confirmed in-AAR, no dep bump) → decode file → `List<Segment{startMs,endMs,text}>`, punctuated.
- `TranscriptionModelProvider`: downloadable asset reusing **`AsrModelProvider`** download-to-disk + load-by-path (default Whisper base ~200 MB, tiny ~100 MB option). *Confirm exact int8 sizes before a storage budget.*
- `TranscriptionWorker` (**WorkManager, FGS-backed**): chunk long audio, stream segments to `NotesRepository` as they complete, bounded memory, resumable + cancellable, progress; status PENDING→RUNNING→DONE/FAILED. **Not** a bare coroutine.
**Tests:** decode a short fixture WAV → expected segments (skip if model absent in CI); Worker status transitions + cancel (WorkManager test harness).
**Acceptance:** a recorded file transcribes to punctuated text durably across backgrounding; CI green.

## Phase 3 — Summarization — owner **nebula** — needs P2b
**Files:** create `feature/.../notes/SummarizeTranscriptUseCase.kt` (+ consent plumbing).
**Contract:** build prompt (title + key points + action items) → existing `LlmProvider.stream()` (mirror `ChapterRecap`); summary language follows `transcriptLang`. **Consent gate:** only on explicit "Summarize" (or a global opt-in) AND a configured provider; else keep transcript, no send. This is the only off-device text path.
**Tests:** fake `LlmProvider` (hand-rolled, `= Unit` defaults) → prompt shape + streamed assembly; consent gate blocks when no provider/consent.
**Acceptance:** transcript → titled summary on consent; no-provider path is a graceful no-op; CI green.

## Phase 4 — Notes UI — owner **luna-sectionC** (+ **luna-ui**) — needs P1, stubs P2/P3
**Files:** create `feature/.../notes/ui/{NotesListScreen,RecordScreen,NoteDetailScreen,NotesViewModel}.kt`; nav route in `StoryvoxNavHost.kt` (+ bottom/nav entry "Notes").
**Contract:**
- List: cards (title, snippet, date, mic/pencil glyph, duration, status chip) + search + primary Record FAB + "New note" (typed).
- Record screen: waveform, timer, pause/stop → create note + enqueue transcription.
- Detail: audio player (play/scrub, tap segment→seek), read-only transcript, summary (+"Summarize" if pending), editable body, tags, delete/export.
- `core-ui` theme, dark/light, a11y (TalkBack labels).
**Tests:** Compose `createComposeRule()` for list/record/detail (per `androidTest` launch gotchas — debug manifest overlay + GrantPermissionRule).
**Acceptance:** full record→transcribe→(summarize)→note flow navigable on-device; CI green.

## Cross-cutting verification (owner: **nebula** review each PR)
- Never log note content (`Log.w/e` survive release).
- Backup-exclusion test is the privacy gate — must pass before any note data ships.
- Adversarial review each phase PR before merge (focus: privacy leaks, DI-at-`:app`, error/edge paths).

## Self-review (spec coverage)
§3.1 Capture→P2a · §3.2 Transcription→P2b · §3.3 Summarize→P3 · §3.4 Storage→P1 · §3.5 Sync→deferred (not in plan, correct) · §3.6 UI→P4 · §3.7 Privacy/backup→P1 + cross-cut · §4 error paths→each phase's tests · §6 testing→per-phase Tests. No gaps.
