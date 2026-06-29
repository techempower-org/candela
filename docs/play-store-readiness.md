---
layout: default
title: Candela · Play Store Readiness
description: Consolidated Play Store submission-readiness reference for Candela — content rating, app access, data safety, listing, and assets, grounded in the actual codebase.
---

# Play Store Readiness (#1302)

Consolidated submission-readiness reference for the Candela Play Store listing. Every answer below is grounded in the actual codebase (`AndroidManifest.xml`, the `@SourcePlugin` source roster, the About/Settings screens) — not boilerplate.

This doc is the **umbrella**; it references the authoritative source-of-truth files rather than duplicating them (those drift — see #1301):

- **Policy analysis** → [`play-store-policy-check.md`](play-store-policy-check.md) (UGC, foreground services, permissions, Data Safety pre-draft)
- **Listing copy** → [`play-store/listing/`](play-store/listing/) (`title.txt`, `short-description.txt`, `full-description.txt`)
- **Privacy policy** → [`privacy.md`](privacy.md) (live at `https://candela.techempower.org/privacy/`)
- **Graphics + screenshots** → [`play-store/v1.0/`](play-store/v1.0/) + [`play-store/RUNBOOK.md`](play-store/RUNBOOK.md)
- **Data Safety** → `play-store-policy-check.md` §4 (#1139)

> **Three blockers found during this audit** — see the flagged ⚠️ items: (1) the v1.0 **screenshots are stale** (show the removed 988/Emergency-Help card); (2) **AO3 + Royal Road ship default-ON**, which drives the content rating to Teen and contradicts the `SourceIds` kdoc; (3) the full description sits at **3996/4000 chars** (4 to spare).

---

## 1. Content Rating (IARC questionnaire)

Candela is a **reader/audiobook player**: it authors no violent, sexual, or gambling content of its own. The rating is driven entirely by **what content the app gives access to**.

**What the app surfaces by default** (verified from `@SourcePlugin(defaultEnabled = …)` — the annotation is the source of truth):
- **Unrestricted web access** — the magic-link Readability source (`defaultEnabled = true`) opens *any* HTTP(S) URL the user pastes/shares; RSS opens any feed; Google News + Hacker News surface live web headlines.
- ⚠️ **Mature-capable sources ON by default** — **Archive of Our Own** (`defaultEnabled = true`) can surface **Explicit-rated** fanfiction, and **Royal Road** (`defaultEnabled = true`) hosts mature web-fiction. *(Note: the `SourceIds.AO3` kdoc still says "Default OFF … because AO3 content can be Explicit-rated" — the annotation overrides it; the kdoc is stale. Flipping these two to `defaultEnabled = false` would lower the rating floor — a product decision for JP.)*

**Suggested IARC answers:**

| Question | Answer | Basis |
|---|---|---|
| Violence (cartoon/realistic) | **None** | App authors none |
| Sexual content / nudity | **None by the app; possible via user-selected sources** | AO3 Explicit + open web are reachable; answer honestly that the app provides access to user-generated content that may include it |
| Profanity / crude humor | **None by the app; possible via UGC/web** | Same |
| Controlled substances / gambling | **None** | No such content or mechanics |
| **Users can interact / share content** | **Yes (limited)** | Optional cloud sync; read-only chat-source ingestion (Discord/Telegram/Slack/Matrix); a one-tap deep-link to an external peer-support Discord. No in-app posting or social graph. |
| **Unrestricted access to the internet** | **Yes** | Magic-link reader + RSS open arbitrary web content |
| Shares user location | **No** | No location permission (see §6) |
| Digital purchases | **No** | No IAP, no ads (see §5) |

**Recommended rating: Teen / 13+** (ESRB Teen · PEGI 12 · USK 12 equivalents), **not "Everyone."** The unrestricted-web access plus default-ON mature-capable sources make an "Everyone" rating inaccurate. Expect a **"Users Interact"** and **"Unrestricted Internet"** descriptor. Matches §4 Target Audience.

---

## 2. App Access (instructions for the Play review team)

**All core functionality is testable with no login, no credentials, and no special access.** Paste this into Play Console → App access → "All functionality is available without special access":

> Candela is a free read-aloud reader. On first launch it opens the TechEmpower home with **no account required**. To review core functionality:
> 1. **Browse → pick any default source** (Project Gutenberg, Standard Ebooks, Wikipedia, Hacker News, Google News, arXiv, PLOS, LibriVox, Internet Radio) — all are free, public, and need no login.
> 2. Open any title → tap **Play**. A neural voice downloads once (a few MB) and reads aloud fully on-device.
> 3. **Magic link:** share any web article URL into Candela (or paste it in Add-by-URL) to test the readability reader.
> 4. **Local files:** open an EPUB/PDF/text file, or use the camera/gallery **OCR** to read printed text.
>
> **Optional, third-party-credential features (not required to review the app):** cookie sign-in for Royal Road/AO3 follows; bot tokens for Discord/Telegram/Slack/Matrix; a Notion integration token; a Microsoft Azure key for optional cloud voices; and cross-device cloud sync (email sign-in). Candela bundles **no** credentials for these — they are user-supplied accounts on third-party services, so no test login can be provided; none gates the core browse-and-listen experience.

---

## 3. Privacy Policy

**Exists and is live.** ✅
- Hosted: **`https://candela.techempower.org/privacy/`** (verified HTTP 200 — see #1301; do **not** use `/privacy-policy/`, which 404s).
- Source: [`docs/privacy.md`](privacy.md).
- In-app: **Settings → About → Privacy Policy** opens the hosted page (`feature/.../settings/AboutSettingsScreen.kt`, `PRIVACY_POLICY_URL`).
- Plain-language summary: nothing leaves the device without an explicit user action; no analytics, no ads, no tracking.

Use the `/privacy/` URL in the Play Console listing's Privacy Policy field. *(Privacy-policy review is also tracked by the parallel privacy workstream.)*

---

## 4. Target Audience & Content

- **Target age group: 13 and over.** Matches the §1 Teen rating.
- **Not child-directed** → **not** eligible for / opted into **Designed for Families**. (Mixed/general audience; the unrestricted web + mature-capable sources make a child-directed declaration inappropriate.)
- Appeals to children? **No.**

---

## 5. Ads Declaration

**Contains ads: No.** ✅ The app integrates no ad SDK or ad network; there is no advertising surface anywhere. ("No ads" is also a stated product promise in the listing and privacy policy.)

---

## 6. Data Safety

**Pre-drafted and tracked in #1139** — authoritative mapping in [`play-store-policy-check.md`](play-store-policy-check.md) §4. Summary for the form:

- **Default posture:** all data (library, reading positions, bookmarks, highlights, notes, settings) stays **on-device**. No analytics, no ads, no tracking.
- **Optional cloud sync (off by default):** when the user signs in, library/follows/positions/bookmarks/highlights/notes/pronunciation-dictionary/settings sync to the user's own **InstantDB** backend — **encrypted in transit**; any saved API keys are **end-to-end encrypted** behind a user-held passphrase. Users can delete synced data via the in-app **"Delete cloud data"** action (sign-out only revokes the token + wipes local state). → Data Safety: *Data is encrypted in transit* = Yes; *Users can request deletion* = Yes.
- **No third-party sharing.** Optional features call third parties **only at the user's direction with the user's own credentials** (Azure TTS, the BYO-key AI assistant, the chat/Notion sources) — those providers' privacy policies apply to that traffic.

**Permissions** (`AndroidManifest.xml`, all justified — see `play-store-policy-check.md` §5): `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `FOREGROUND_SERVICE_DATA_SYNC` (playback + chapter pre-render), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NOTIFICATION_POLICY` (sleep-timer DND), `CAMERA` (**optional**, OCR). `uses-feature` camera + telephony are both `required="false"` (tablet-installable). **No** location, contacts, microphone-recording, SMS, or broad storage (file access is via SAF). The two foreground-service types must be declared in the Console's foreground-service form.

---

## 7. Store Listing

Copy lives in [`play-store/listing/`](play-store/listing/) *(owned by the parallel listing-copy workstream — referenced here, not duplicated)*:

| Field | Value | Length |
|---|---|---|
| **Title** | `Candela: Read Aloud` | 19 / 30 ✅ |
| **Short description** | `Free books, tech guides, and the web — read aloud by on-device voices.` | 72 / 80 ✅ |
| **Full description** | see `full-description.txt` | ⚠️ **3996 / 4000** — only 4 chars of headroom; any edit must re-check the count (note: the file is 4040 *bytes*, but Play counts *characters*, and the em-dashes/bullets are multi-byte). |
| **Category** | **Books & Reference** | brief's recommendation |
| **Tags** | audiobook, text-to-speech, reader, accessibility, ebooks | confirm in Console |
| **Contact email / developer phone** | DRAFT — JP to set on the developer profile | see `play-store/listing/AUDIT.md` |

---

## 8. Screenshots

Required: ≥4 phone; a tablet set is recommended (Candela is tablet-first for accessibility). Screens to capture:

1. **Library** — hero card + Continue Listening + bottom nav
2. **Reader** — hybrid reader with synced brass sentence-highlight (the signature surface)
3. **Browse** — a source grid / browse-resources
4. **Voice selection** — the voice picker (4 neural families)
5. **Settings** — accessibility + playback settings
6. *(nice-to-have)* Fiction detail; teleprompter; Android Auto / lock-screen

**Status:** a v1.0 set exists in [`play-store/v1.0/`](play-store/v1.0/) — 4 phone + 6 tablet PNGs (correct dimensions) with captions in `INDEX.md`.

⚠️ **They are stale and must be re-captured before submission.** They were shot against **v0.5.66 (May 2026)**, which predates #775's removal of the **988 / Emergency-Help** card. `phone-02-techempower-home.png` shows an "Emergency Help" card and its caption reads *"Dial 211 or 988"* — both gone from the current build (the app now dials **211 only**; `EmergencyTarget` has a single `Help211` value). Re-shoot against the current release and drop the 988 caption. *(Screenshot planning is owned by the parallel screenshot workstream — flagging the staleness here.)*

---

## 9. Feature Graphic

**Exists.** ✅ `play-store/v1.0/feature-1024x500.png` — 1024×500 opaque RGB (no alpha), Library-Nocturne palette, right ~30% kept clear for the overlaid install button. Regenerable from [`play-store/feature-graphic.html`](play-store/feature-graphic.html) via headless Chrome (command in the file header). No action needed unless the brand/tagline changes.

---

## Readiness summary

| Area | Status |
|---|---|
| Content rating | Ready — answer **Teen/13+** per §1; decide AO3/Royal-Road default-on first |
| App access | Ready — §2 reviewer note (no login needed) |
| Privacy policy | ✅ live at `/privacy/` |
| Target audience | Ready — 13+ |
| Ads | ✅ none |
| Data safety | Ready — #1139 pre-draft |
| Listing copy | Ready — ⚠️ full desc at 3996/4000 |
| Screenshots | ⚠️ **re-capture** (stale 988 card) |
| Feature graphic | ✅ present |

The remaining true blockers are operational/manual (developer account + $25 + ID, keystore + AAB + Play App Signing, the Console forms, screenshot re-capture) — tracked in `play-store-policy-check.md` §7 and #1302.
