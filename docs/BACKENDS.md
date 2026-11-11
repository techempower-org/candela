# Backends: storyvox

Standing rule (JP 2026-10-27, "Backends grow continually"): every project that talks to outside sources keeps this file as a tiered backlog. Each row names the backend, the account or key it needs, its terms caveat and its status. Lanes add one adapter per idle slot through the normal gates (fixtures, red perturbations, review, deploy); every sign-up or plan change is JP's one click, batched through the ask queue.

storyvox's fiction sources are the 53 `source-*` modules (CLAUDE.md); this file tracks the **speech** side (voices, speech recognition for the kids' reading features) and any non-source outside service. Started 2026-11-11 (money-e9) from the DrivenData newsletter of 2026-11-11 (Gmail 1a1caea7265e283c, starred by JP).

Status words: **live** (in code and serving) · **candidate** (not started) · **evaluated** (tried, verdict recorded).

## Tier A: real API or model, in code

| Backend | What it does here | Key / account | Terms caveat | Status |
|---|---|---|---|---|
| Azure Speech (Dragon HD voices), `source-azure` | Cloud voice for narration | Azure Speech key (vault) | Recorded output uses Dragon HD per the fleet rule; per-character billing | live (CLAUDE.md, source-azure) |
| sherpa-onnx / local voices | On-device TTS | none | model licences per voice | live (see the TTS registry in CLAUDE.md) |

## Tier C: candidates

| Backend | What it would do here | Key / account | Terms caveat | Status |
|---|---|---|---|---|
| DrivenData **pasketti-word** (Qwen3-ASR-1.7B fine-tune) and **pasketti-phonetic** (WavLM-large fine-tune) — children's speech recognition, [huggingface.co/drivendata/pasketti-word](https://huggingface.co/drivendata/pasketti-word), [huggingface.co/drivendata/pasketti-phonetic](https://huggingface.co/drivendata/pasketti-phonetic) (both updated 2026-10-23; built from the On Top of Pasketti challenge winners, retrained on more data; DrivenData: "outperform leading open alternatives") | A read-along / "read it back" check for kids: word-level recognition of a child reading a passage aloud (pasketti-word), and phone-level scoring for early readers (pasketti-phonetic); runs on familiar (GPU) behind the existing Wyoming/local speech route rather than on the phone (1.7B parameters) | none (open weights on Hugging Face) | Licence **bigcode-openrail-m** on both (use restrictions attached; read before shipping to users); children's audio never leaves the LAN (no cloud STT for kids' voices); check the base models' own licences (Qwen3-ASR, WavLM) | candidate (2026-11-11); first step: a familiar-side eval on a few recorded read-alouds against Parakeet/Whisper, then a `speech-to-cli` STT route (see that project's BACKENDS.md) that storyvox calls |
