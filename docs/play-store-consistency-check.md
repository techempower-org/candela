# Play Store docs — cross-consistency review

_Flag-only pass (no fixes) across the Play Store submission docs, for a batch-fix. Lucid, 2026-06-29._

## Which version of each doc was reviewed

Two docs have a newer version in-flight than what's on `main`'s working tree — reviewed the **current intended** version of each:

| Doc | Version reviewed | Note |
|---|---|---|
| `play-store-walkthrough.html` | **v2 in PR #1357** (commit `b2694916`) | `main` working tree still has the stale pre-rewrite copy until #1357 merges |
| `play-store/listing/full-description.txt` | **v2 in PR #1357** | same |
| `play-store-readiness.md` | `feat/1302-readiness-doc` (worktree) | not on `main` yet |
| listing.md · short-description.txt · privacy.md · data-safety-checklist.md · policy-check.md · RUNBOOK.md | `main` | — |

`play-store-listing.md` is reverie's in-flight draft; some mismatches below may already be queued in their edits.

---

## Summary

| # | Severity | Topic | Conflicting docs |
|---|---|---|---|
| C1 | 🔴 Critical | "Sign-out deletes cloud data" — false per code | privacy, walkthrough, readiness, policy-check **vs** data-safety-checklist |
| C2 | 🔴 Critical | "Data shared with third parties?" — Yes vs None | data-safety-checklist (**Yes**) vs walkthrough + readiness (**None**) |
| C3 | 🔴 Critical | Privacy policy under-discloses synced data (omits API-key sync) | privacy vs walkthrough/readiness/data-safety-checklist |
| M1 | 🟠 Medium | AI providers: "Google" vs "Ollama" | privacy + listing vs walkthrough + full-description |
| M2 | 🟠 Medium | App title differs | title.txt/readiness/walkthrough vs listing.md drafts |
| M3 | 🟠 Medium | Short description differs | short-description.txt/readiness vs listing.md drafts |
| M4 | 🟠 Medium | Stale **988** / Emergency-Help in screenshots + captions | listing.md screenshot brief + v1.0 PNGs |
| M5 | 🟠 Medium | Narrow "email + library state" Data-Safety summary | listing.md + policy-check §4 vs expanded payload |
| M6 | 🟠 Medium | "Piper — dozens of languages" misattribution | listing.md (fixed in full-description v2) |
| L1–L7 | 🟡 Low | source-list lag, KittenTTS size, char count, contact email, AO3 kdoc, System-TTS omission, the #1357 stale-copy meta | see below |

---

## 🔴 Critical (Data Safety / privacy — rejection & legal-accuracy risk)

### C1 — "Sign-out deletes your cloud record" is contradicted by the code (4 docs wrong, 1 right)
- **Say sign-out/sync-off deletes the cloud copy:** `privacy.md` §2.1, §5, §8; `play-store-walkthrough.html` §IV (Email-row note + check c4b); `play-store-readiness.md` §6; `play-store-policy-check.md` §4.
- **Correct, code-verified:** `data-safety-checklist.md` (#1139) — sign-out only **revokes the token + wipes local**; cloud deletion is a separate explicit **"Delete cloud data"** action (`purgeRemoteData`, #1248) plus an email-request backstop for the `$users`/email record.
- **Fix direction:** align all four to the data-safety-checklist wording. The Play Console "users can request deletion" answer must point at the in-app Delete-cloud-data action + email, **not** sign-out. Privacy policy §2.1/§5/§8 must be corrected and must name a real deletion-request email (data-safety-checklist open-item #2).
- ⚠️ Note: the fix for the walkthrough + full-description touches **PR #1357** content.

### C2 — "Data shared with third parties?" — docs disagree on the actual form answer
- `data-safety-checklist.md` §B/§C: declare **Email + User ID + App-activity = Shared: Yes** (with InstantDB).
- `play-store-walkthrough.html` §IV c4a: **"Data shared → None"** (InstantDB treated as a processor; alt noted parenthetically). `play-store-readiness.md` §6: **"No third-party sharing."**
- Both interpretations are defensible (processor vs third party), but the Console takes **one** answer and it must match the privacy policy. **Needs a decision, then alignment.**

### C3 — Privacy policy under-discloses what syncs (notably the API keys)
- `privacy.md` §2.1 lists synced data as "what fictions you've added, current reading position, voice preferences, settings."
- Actual payload (per `data-safety-checklist.md` Task 1, `play-store-readiness.md` §6, walkthrough v2): library, **follows, bookmarks, highlights + notes, pronunciation dictionary**, settings — **plus end-to-end-encrypted API-key secrets** (Azure/LLM/source tokens).
- Privacy policy should disclose that **saved API keys sync (E2E-encrypted)** and the broader app-data set. Under-disclosure vs observed behavior is a rejection/again-legal-accuracy risk.

---

## 🟠 Medium

### M1 — AI provider list conflicts
- `privacy.md` §2.5 + `play-store-listing.md` full-desc/features: **"OpenAI, Anthropic, Google, …"**
- `play-store-walkthrough.html` + `full-description.txt` v2: **"Anthropic, OpenAI, Ollama"** (no Google; adds local Ollama).
- Root cause: only Claude/OpenAI/Ollama are user-selectable today; Vertex/Bedrock/Foundry/Teams are "coming soon" (per `ProviderId.kt`). Pick the canonical public list and use it everywhere. (Lead already chose the selectable set for the walkthrough.)

### M2 — App title not reconciled
- `listing/title.txt` + `readiness` §7 + walkthrough chips: **"Candela: Read Aloud"** (19 ch).
- `play-store-listing.md` recommends **"Candela: read aloud for all"** (27 ch) as #1.
- Decide one; `title.txt` is what gradle-play-publisher uploads.

### M3 — Short description not reconciled
- `listing/short-description.txt` + readiness: **"Free books, tech guides, and the web — read aloud by on-device voices."**
- `play-store-listing.md` recommends **"Free books, tech guides, and accessible help — read aloud by TechEmpower."**
- Same: `short-description.txt` is the uploaded source of truth.

### M4 — Stale **988** / Emergency-Help in the screenshot materials
- `play-store-listing.md` screenshot brief, panel 5: surface "211 + 988", caption **"Dial 211 or 988."**
- The shipped `play-store/v1.0/` PNGs show the removed **Emergency-Help/988 card** (flagged in `readiness` §8 — shot against v0.5.66, pre-#775).
- 988/911 were removed in #775; the app dials **211 only**. `privacy.md` §2.7, walkthrough, full-description are all correctly 211-only — only the screenshot materials lag. Re-shoot + drop the 988 caption.

### M5 — Narrow Data-Safety summaries lag the expanded payload
- `play-store-listing.md` §"Data Safety form": "collects email + library state, … deletable by the user" — narrow + vague deletion.
- `play-store-policy-check.md` §4 still says **"sign out of sync deletes the record"** even though `data-safety-checklist.md` claims §4 "was corrected ✅" — the correction **did not land in `policy-check.md` on main**. Reconcile (ties to C1).

### M6 — "Piper — dozens of languages" misattribution
- `play-store-listing.md` full-desc: "Piper (compact, dozens of languages)."
- Catalog reality (fixed in `full-description.txt` v2): Candela's **Piper** voices are English-only (45, US+UK); **Kokoro** is the multilingual family (53 voices / 9 languages). Update listing.md to match.

---

## 🟡 Low / nits

- **L1** — `privacy.md` §2.2 backend list (~21 named) omits **Google News, Bookshare, LibriVox** (and import types). No count is claimed, but the list lags the "25 sources" all other docs cite.
- **L2** — KittenTTS model size: `listing.md` "24 MB" vs walkthrough/full-description/code "~25 MB." Pick one.
- **L3** — Full-description length: `readiness` §7 says **3996/4000**, the v2 file measures **3995**. Both fit; margin is ~5 chars, so any edit must re-count (Play counts characters, not bytes).
- **L4** — Contact email placeholder differs: `privacy.md` uses `jp@jphein.com`; `listing.md` says "`claude2@techempower.org` or `jp@jphein.com` (DRAFT)." Resolve before submission (also the deletion-request email for C1).
- **L5** — `SourceIds.AO3` kdoc still says "Default OFF" but the `@SourcePlugin` annotation is **default-ON** (code/doc, flagged in `readiness` §1). Relevant because default-ON AO3/Royal Road is what drives the Teen rating every doc assumes — worth confirming the product intent.
- **L6** — **System TTS** (the device's built-in voices, the 6th backend) is named only in the walkthrough; `listing.md` et al. say "4 families + Azure" and omit it. Minor completeness gap.
- **L7** — Meta: `play-store-walkthrough.html` + `full-description.txt` have a **stale copy on `main`** vs the v2 in **PR #1357**; this review used v2. `main` aligns once #1357 merges — don't batch-fix the stale `main` copies directly, edit on the PR branch.

---

## Consistent across docs (no action)
- **Version:** all reference **v1.4.5** or are version-agnostic; no doc cites a wrong current version. (`policy-check.md`'s "v1.0 prep cycle, May 2026" framing is old but not contradictory.)
- **Source count:** **25** is consistent (walkthrough, full-description, listing.md, readiness).
- **Voice families:** **4 on-device** (Piper, Kokoro, KittenTTS, Supertonic) is now consistent across walkthrough, full-description, listing.md, readiness — Supertonic present everywhere.
- **No analytics / no ads / no ad-ID / on-device OCR / HTTPS-only:** consistent across privacy, walkthrough, readiness, policy-check.
- **Content rating:** Teen 13+ + UGC advisory, not child-directed — consistent across listing.md, readiness, policy-check, walkthrough, RUNBOOK.
- **Privacy-policy URL:** `candela.techempower.org/privacy/` — consistent.
- **Keystore SHA256 / `storyvox.*` build-property paths:** walkthrough §VI and RUNBOOK match (the `storyvox`-prefixed keystore/property names are intentionally unchanged post-rebrand).

_Out of scope (not in the 9): `docs/play-store-signing.md` exists and may carry keystore claims — worth a glance if signing details are being batch-edited._
