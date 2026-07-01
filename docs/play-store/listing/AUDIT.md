---
layout: default
title: Candela · Play Store listing audit + drafts
description: Audit of existing Play Store listing copy and corrected, limit-compliant drafts.
---

# Play Store listing — audit + corrected drafts

Audited 2026-06-26 against app **v1.1.5** (versionCode 233, `org.techempower.candela`). Current release: **v1.6.3 / vc257** — this audit predates it; copy may need a refresh.
Drafts in this folder are **publish-ready text** for the gradle-play-publisher
layout; ranked alternates + rationale live here.

## TL;DR

Listing copy already exists in **three** places, and they disagree:

| Source | State | Problem |
|---|---|---|
| `app/src/main/play/listings/en-US/` (live publish path) | **Stale** | Title + full description still say **"storyvox"** (pre-rebrand). Title is **33 chars > 30 limit**. Release notes **519 chars > 500 limit**. gradle-play-publisher **auto-uploads these** on `publishReleaseBundle` → a stale, over-limit listing would ship. |
| `docs/play-store-listing.md` (design brief, PR #610) | Good base, dated | Candela-branded + great IARC/data-safety detail, but assumes the **old 50-char title limit** (real limit is **30** — every title draft in it is over), and says "21 backends" (now 25). |
| `README.md` | Current truth | "Candela", **25 backends**, 3 voice families, AI chat, Wear OS, TechEmpower-first. |

**The live publish-path files are a release hazard, not just a doc nit.** They
should be corrected (de-"storyvox", trimmed to limits) before the next
`publishReleaseBundle`. This folder holds the corrected copy ready to drop in.

## Corrected drafts in this folder (all within Play limits)

| File | Limit | This draft | Maps to (publish path) |
|---|---|---|---|
| `title.txt` | 30 | **19** ✅ | `app/src/main/play/listings/en-US/title.txt` |
| `short-description.txt` | 80 | **70** ✅ | `…/short-description.txt` |
| `full-description.txt` | 4000 | **3196** ✅ | `…/full-description.txt` |
| `release-notes-template.txt` | 500/release | example **421** ✅ | `app/src/main/play/release-notes/en-US/default.txt` |

### Title — `Candela: Read Aloud` (19) ✅ recommended
Play's title limit is **30 chars** (not 50 — the brief is wrong on this). Brand +
the core verb; "read aloud" is the discovery keyword for the TTS/audiobook intent.
Ranked alternates (all ≤30):
1. `Candela: Read Aloud` (19) ✅
2. `Candela: books read aloud` (25)
3. `Candela — listen to anything` (28)
4. `Candela: free tech help, aloud` (30) — leads with the TechEmpower mission, at the limit.

> The TechEmpower mission can't fit a 30-char title alongside the brand + a
> function keyword; it carries in the short/full description and the
> "TechEmpower" developer name shown on the listing. Flagging in case JP wants
> mission-first (option 4) over discovery-first (option 1).

### Short description — 70/80 ✅
`Free books, tech guides, and the web — read aloud by on-device voices.`
Alternates:
- `Free books, tech guides, and accessible help — read aloud by TechEmpower.` (73) — mission-forward (the brief's pick).
- `Listen to free books, guides, fiction, and the web — beautiful on-device voices.` (79) — function-forward.

### Full description — 3196/4000 ✅
Rewritten from the brief's draft: **"storyvox" → "Candela"**, **21 → 25 sources**,
added LibriVox / Radio / OCR+PDF / per-book AI chat / Wear OS, kept the TechEmpower
mission framing, accessibility, and offline-first posture. 800 chars of headroom
for tuning.

## Content rating (IARC questionnaire) — answers + flags

The brief (`docs/play-store-listing.md`) and `RUNBOOK.md` already align on this;
consolidated here so it's in one place for the questionnaire.

**Recommended outcome: Teen (13+) with a user-generated-content advisory.**

| IARC question | Answer | Why |
|---|---|---|
| Violence / sexual content / profanity / drugs / gambling *in the app itself* | No | Default surfaces are TechEmpower's resource library + a neutral library. |
| Users can access **user-generated / unmoderated** content from third parties | **Yes** | Readability magic-link, Royal Road, AO3, RSS, Discord/Telegram/etc. fetch user-supplied URLs Candela does not moderate → this is what drives the 13+ rating. |
| Users interact with each other / in-app messaging | **No** | Discord/Slack/Matrix/Telegram backends are **read-only feeds**; you can't post from inside Candela. |
| Shares user location | No | No location features/permission. |
| Digital purchases / IAP / real-money | No | Free, no IAP, no ads. |
| Shares personal info with third parties | No | Optional sync sends *your* email to *your* InstantDB sync, opt-in — not third-party sharing. |

**Open items needing a human decision before submission:**
- [ ] **Target audience: 13+** (must match the content rating; NOT child-directed → not eligible for Designed for Families).
- [ ] **Category: Books & Reference** (brief's recommendation; confirm).
- [ ] **Contact email** on the listing — brief leaves as DRAFT (`claude2@techempower.org` or `jp@jphein.com`?).
- [ ] **Developer phone** (Play requires one on the developer profile).
- [x] **Privacy policy URL** — use **`https://candela.techempower.org/privacy/`** (verified resolving — **HTTP 200**, 2026-06-28). ⚠️ Do **NOT** use `/privacy-policy/` — it **404s** (verified). `docs/privacy.md` is served at `/privacy/` on the static microsite (it carries no conflicting `permalink`), and the in-app link (`feature/.../settings/AboutSettingsScreen.kt`), `docs/play-store-policy-check.md`, and `docs/index.md` all already use `/privacy/`. Play **requires** a resolving privacy-policy URL or the submission is rejected, so use the `/privacy/` form. _(Earlier guidance here had this backwards — the static deploy serves the filename path `/privacy/`, not `/privacy-policy/`; corrected per #1301.)_

## Data Safety + permissions (pointers — already drafted)

- **Data Safety form**: see `docs/play-store-policy-check.md`. Summary: nothing
  collected by default; with optional sync, collects email + library state
  (encrypted in transit, user-deletable, never sold). No ads, no ad IDs.
- **Sensitive permissions to justify** at the Play Console permissions screen
  (from `app/src/main/AndroidManifest.xml`):
  - `CAMERA` → on-device OCR only (scan a page → text); images never leave the device.
  - `POST_NOTIFICATIONS` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `_DATA_SYNC` → audiobook playback notification + background chapter sync.
  - `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` → resume playback / keep audio alive.
  - Justification copy lives in `docs/play-store-policy-check.md`.

## Recommended next action

1. Pick final title + short-description variants (or accept the recommended).
2. Copy these four drafts into `app/src/main/play/…` to replace the stale
   "storyvox" copy **before the next `publishReleaseBundle`** (per `RUNBOOK.md`,
   that path auto-uploads). I can apply that move in this PR on your say-so —
   left out for now since the task was "draft into docs/play-store/listing/".
3. Answer the 5 open content-rating/contact items above.
4. Graphics already staged in `docs/play-store/v1.0/` + `app/src/main/play/.../graphics/` — no change needed unless the rebrand changed the icon/wordmark.
