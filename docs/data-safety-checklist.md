# Data Safety declaration — verification + Play Console checklist (#1139)

_Verified by Reverie against the candela code (main) — not taken on faith from the doc._

## TL;DR / verdict
- **"Users can request deletion = Yes" is TRUTHFUL** — but the doc §4's *mechanism* is **wrong**. Deletion is NOT via sign-out; it's a separate explicit in-app **"Delete cloud data"** action (#1248) plus an email-request backstop.
- **All synced *app* data is deletable in-app** (8 syncer domains, verified).
- **One gap:** the InstantDB `$users` email/identity record has **no in-app delete path** — it relies on the privacy-policy email-request channel. That channel must exist for the "Yes" to be fully honest.
- **Independent security review → No** for v1.0 (confirmed).
- **Net Data Safety recommendation: ship the §4 mapping as-is**, with the two corrections below.

---

## Task 1 — Deletion-flow verification (the important one)

**Claim under test (doc §4 line 225):** _"Users can request deletion: Yes (sign out of sync deletes the record; uninstall deletes on-device data)."_

**What the code actually does:**

1. **Sign-out does NOT delete cloud data.** `SyncAuthViewModel.signOut()` → `InstantClient.signOut(refreshToken)` which POSTs `{appId, refreshToken}` to `…/runtime/signout`. That **revokes the refresh token only**; its kdoc says _"Sign the refresh token out server-side. Local state must be cleared by the caller."_ It then wipes the **local** session (`session.clear()`, #1217). The cloud record is **left intact**. So the doc's "sign out deletes the record" is **inaccurate** — sign-out = token revoke + local wipe.

2. **Deletion exists as a separate, explicit action.** `SyncAuthViewModel.purgeRemoteData()` (#1248) — _"explicitly delete the user's cloud (InstantDB) record"_ — calls `SyncCoordinator.purgeRemoteData(user)`, which loops **every** registered syncer and calls `syncer.purge(user)` → `HttpInstantBackend.delete()` → an admin `["delete", entity, id]` transact step (`POST …/admin/transact`). This is a real server-side row delete.

3. **Coverage = all 8 synced domains** (every `@IntoSet Syncer` in `SyncModule`, each implements `override suspend fun purge`):
   Library · Follows · PlaybackPosition (reading positions) · PronunciationDict · Bookmarks · Annotations · Secrets · Settings (voice prefs).
   → Everything the form declares as collected/shared **app data** (library state = fiction IDs, positions, voice prefs) is covered. ✓

4. **Gap — the email / `$users` identity record.** No code path deletes the InstantDB `$users` row (the magic-code auth principal holding the email). That's expected — the as-token admin API can't delete its own auth principal — but it means **"email" deletion is NOT in-app**; it relies on the privacy-policy email-request path. The issue body already promises both ("add … a documented email-request path") — so this is fine **iff** the privacy policy documents a real deletion-request email.

**Verdict:** "deletion = Yes" stands, but on the correct basis: in-app **Delete cloud data** (#1248) for synced app data + email-request for the identity record. Sign-out is irrelevant to deletion.

### Required corrections
- **Doc §4:** the "sign out of sync deletes the record" wording was corrected in this same change to describe the real path (in-app **Delete cloud data** action #1248 + email-request for the identity record; uninstall clears on-device data). ✅ done.
- **Privacy policy:** ensure it lists a working deletion-request email. This backstops the `$users`-record gap and is what Play expects behind a "Yes."
- **In Play Console:** when entering the deletion answer, point users at the **Delete cloud data** action + the email, **not** sign-out.

---

## Task 2 — Play Console Data Safety: copy-paste checklist

Work top-to-bottom through the Console wizard. **Bold = the option to pick.**

### Section A — Data collection overview
- _Does your app collect or share any of the required user data types?_ → **Yes**
- _Is all of the user data collected by your app encrypted in transit?_ → **Yes**
- _Do you provide a way for users to request that their data be deleted?_ → **Yes**

### Section B — Data types (mark only these as collected; everything else = **Not collected**)

**Personal info → Email addresses**
- Collected **Yes** · Shared **Yes** · Processed ephemerally **No**
- Required or optional → **Optional** (only if the user turns on sync)
- Purposes → **App functionality** + **Account management**

**Personal info → User IDs** (the InstantDB record id)
- Collected **Yes** · Shared **Yes** · Processed ephemerally **No**
- **Optional** · Purpose → **App functionality**

**App activity → Other user-generated content / Other actions** (library state: fiction IDs, reading positions, voice prefs)
- Collected **Yes** · Shared **Yes** · Processed ephemerally **No**
- **Optional** · Purpose → **App functionality**
- ⚠️ _Mapping note:_ Play's taxonomy has no "library state" type. "App activity → Other user-generated content" is the best fit for synced reading state. JP — confirm this bucket; the alternative is "Other actions." Either is defensible; pick one and keep it consistent with the privacy policy.

**Mark as Not collected (select nothing):**
Location (approx/precise) · Financial info · Health & fitness · Messages (SMS/email/other in-app) · Photos & videos · Audio files/voice/music · Files & docs · Calendar · Contacts · App info & performance (crash logs, diagnostics) · Device or other IDs · Web browsing.

> ⚠️ _#1514 note (My Documents wallet):_ the encrypted wallet stores scans of the user's benefits documents (IDs, pay stubs, award letters) **on-device only**, as `EncryptedFile` blobs (metadata manifest included) behind a **device-credential gate** (`KeyguardManager.createConfirmDeviceCredentialIntent` — fingerprint / face / PIN), excluded from cloud backup + device transfer, and **never** part of cloud sync. Nothing is transmitted → **Photos & videos** and **Files & docs** stay **No** (same on-device-only basis as the camera OCR). **No new permission** (the device-credential gate needs none) → Section B and Section C are unchanged.
>
> Reasoning carried from §4: OCR camera images + recognized text are processed **on-device** (ML Kit) and never transmitted → **Photos** stays **No** despite the CAMERA permission. **Audio** stays **No** despite the `RECORD_AUDIO` permission (#1367 recording mode + #1368 voice-paced teleprompter): the audio is saved/transcribed **entirely on-device** (sherpa-onnx) and **never transmitted** — only a one-time recognition-model download touches the network. EPUB/SAF file reads aren't sent anywhere → Files **No**. Discord/Slack/Matrix/Telegram are read via the **user's own credentials direct to the service**; Candela stores nothing → Messages **No**. Calendar events are read via the on-device `CalendarContract` provider, narrated locally, and never transmitted (no cloud API / OAuth / key) → **Calendar** stays **No** despite the `READ_CALENDAR` permission (#1495) — the same on-device-only basis as the camera. No analytics/ads/crash SDK → App activity/info, Device IDs all **No**.

> ⚠️ _#1657 note (Voice Notes):_ records audio and transcribes it **entirely
> on-device** (Whisper) into a **separate** `notes.db` (plus recordings under
> `NotesRepository.RECORDINGS_SUBDIR`), both **excluded from cloud backup +
> device transfer** (`backup_rules.xml` / `data_extraction_rules.xml`) and
> **never** part of cloud sync. No audio ever leaves the device → **Audio
> (files/voice/music)** stays **No**, same on-device-only basis as recording
> mode / the teleprompter (#1367 / #1368). The only egress is a single note's
> *transcript text*, sent to the user's own BYOK AI provider **only** on an
> explicit per-note **Summarize** tap — user-initiated, so it is **not "shared"**
> (same basis as BYOK keys / AI chat in §C). Section B and Section C are
> unchanged.

### Section C — Data sharing
Declare **one** sharing relationship:
- **Email address + library state + User ID → shared with InstantDB** (sync backend), purpose **App functionality**, when sync is enabled.
- **Do NOT declare as "shared":** BYOK Azure/Anthropic/OpenAI keys, and third-party sign-ins (Discord/Notion/Royal Road/etc.). Per Play's definition these are *the user transmitting their own data directly to that service*, not the app sharing it.
  - ⚠️ _#1507 note:_ Notion now also supports an **OAuth "Connect"** flow (in addition to the pasted Integration Token). The classification is unchanged: the OAuth access + refresh tokens are the *user's own* credentials, stored encrypted in `storyvox.secrets` (and, only if cloud sync is on, E2E-encrypted behind the user's passphrase — Candela's servers never see plaintext). Content still flows **user → Notion directly** via those tokens; Candela does not share Notion data with anyone. So Notion stays **Not "shared"** and Files/Messages stay **No**.
  - ⚠️ _#1496 note:_ **Google Drive** adds an **OAuth "Connect"** flow (`drive.file` scope only) so the user can browse their authorized Drive folders. Same classification and reasoning as the Notion OAuth note above: the access + refresh tokens are the *user's own* Google credentials, stored encrypted in `storyvox.secrets` (E2E-encrypted behind the passphrase when sync is on). Drive content flows **user → Google directly** via those tokens; Candela shares nothing. The `drive.file` scope means the app only ever sees files the user explicitly grants — never the whole Drive. Google Drive stays **Not "shared"** and Files/Messages stay **No**. (Opening a single Drive file via the system picker/SAF, unchanged, involves no account credential at all.)
  - ⚠️ _#1492 note:_ **Reddit** adds a BYOK **installed-app OAuth** source (the user's own client id → a read-only, userless bearer). Same classification as the sign-ins above: the client id is the *user's own* credential, stored encrypted in `storyvox.secrets`; Candela mints a read-only token and reads **public** subreddit content **user → Reddit directly**, sharing nothing. Reddit stays **Not "shared"** and Messages stay **No**. Candela ships **no secret** (installed apps have none).

### Impact sharing (#1463) — no new declaration

**Confirmed: opt-in Anonymous Impact Sharing adds NO new "collected" or "shared"
data type.** The feature is a *user-initiated share* (no server, no automatic
transmission, no background upload — see privacy.md §2.9). When the user taps
Share, Candela hands a coarse, rounded, **identifier-free** summary (month, app
`major.minor`, bucketed hours/chapters/books, a set of built-in source IDs) to
whatever app the *user* picks in the Android share sheet, delivered via the
user's own account.

- Reasoning mirrors the OCR/BYOK argument (§B/§C above): egress that the user
  initiates to a destination the user chooses, using the user's own channel, is
  **the user transmitting their own data**, not the app *collecting* or
  *sharing* it in Play's taxonomy — the same basis on which BYOK keys and
  third-party sign-ins are "not shared."
- The payload contains **no data type in Play's personal-data taxonomy**: no
  identifiers (no email, User ID, device ID, or nonce), no personal info, no
  location, no content — and Candela operates no collector, so there is no IP to
  log or retain. There is therefore nothing to add to Section B or Section C.
- Keep policy + form consistent: privacy.md §2.9 documents this flow and §3
  states the app still collects nothing on its own. **`play-store-walkthrough.html`
  §IV needs no change** unless a future Console re-audit disagrees with this
  mapping (per open item #4).

### Section D — Security practices
- Encrypted in transit → **Yes** (HTTPS enforced by `network_security_config.xml`)
- Users can request deletion → **Yes** → provide the in-app **Delete cloud data** path + the deletion-request email (see Task 1 corrections; **not** sign-out)
- Independent security review → **No** (see Task 3)
- Committed to Play Families Policy → **No / N/A** (13+ app with a UGC advisory, not a Families app)

---

## Task 3 — Independent security review

**Confirmed: answer No for v1.0.** No formal third-party security review / pentest has been performed. "No" is the truthful answer; the only effect is the absence of the optional "independent security review" badge — no listing penalty, no policy risk. Revisit post-v1.0 only if JP decides to commission one.

---

## Open items for JP (not blocking the mapping itself)
1. ~~Correct doc §4 deletion wording (sign-out → Delete-cloud-data action + email).~~ ✅ done in this change.
2. Confirm the privacy policy documents a deletion-request email (backstops the `$users` record).
3. Pick the "library state" Play category (App activity → Other UGC vs Other actions) and keep policy + form consistent.
4. Re-audit this form whenever a new data flow lands (e.g., opt-in crash reporting) — a Data Safety answer that contradicts observed network traffic is a top rejection trigger.
   - ✅ _#1463 (impact sharing) audited:_ no new declaration — user-initiated share-sheet egress to a user-chosen destination, no collector, no identifiers (see the "Impact sharing" note above). Candela originates no network traffic for it, so nothing contradicts the form.
5. **#1495 (device calendar):** adds the runtime-dangerous `READ_CALENDAR` permission. Calendar events are read via the on-device `CalendarContract` provider and **never transmitted** (no cloud API / OAuth / key), so the mapping is unchanged: **Calendar = Not collected**, on the same on-device-only basis as the camera. No new sharing relationship — Section C is unchanged. privacy.md §2.10 + the walkthrough §IV permission table document the posture; when a Play reviewer sees the new permission, point them at the on-device-only justification.
7. **#1514 (My Documents wallet):** adds an on-device encrypted document store with a **device-credential gate** (`KeyguardManager` confirm-credential — **no new permission**). Documents (and the metadata manifest) are `EncryptedFile`-encrypted at rest, gated behind fingerprint/face/PIN, excluded from cloud backup + device transfer, and never synced — nothing is transmitted, so the mapping is unchanged: **Photos & videos / Files & docs = Not collected**, on the same on-device-only basis as the camera. No permission added → Sections B and C unchanged. privacy.md §2.13 + §7 + the walkthrough §IV row document the posture; point a Play reviewer at the on-device-only + excluded-from-backup justification.
6. **#1515 (notice deadline keeper):** adds `SCHEDULE_EXACT_ALARM` (normal, install-time) and re-uses the already-declared `POST_NOTIFICATIONS` — both used **only** to deliver **local** reminder notifications. No new data type is collected or shared: the scanned letter rides the existing on-device OCR path (§2.8; Photos/Audio stay **No**), the extracted deadline + the confirmed reminders live in the app's private storage (excluded from backup/sync) and are **never transmitted**. Section B and Section C are unchanged. privacy.md §2.11 + the walkthrough §IV permission table document the posture; when a Play reviewer asks about the exact-alarm permission, point them at the local-reminders-only justification (exact alarms are optional, with an inexact fallback).
9. **#1657 (Voice Notes):** reuses `RECORD_AUDIO` + adds `FOREGROUND_SERVICE_MICROPHONE` (recording FGS). Audio + transcript + note live on-device in a separate `notes.db`, excluded from backup/transfer and never synced; transcription is on-device (Whisper); a note's transcript text egresses only on an explicit per-note **Summarize** tap to the user's own BYOK AI provider. No new data type collected or shared → **Audio = Not collected**, Section B/C unchanged. privacy.md §2.15 + the walkthrough §IV Audio / AI-text rows document the posture; when a Play reviewer sees `RECORD_AUDIO` + the mic FGS, point them at the on-device-only + Summarize-consent justification.
8. **#1367 / #1368 (microphone):** adds the runtime-dangerous `RECORD_AUDIO` permission — recording mode (#1367) + the voice-paced teleprompter's on-device speech-to-text (#1368) — plus a **legacy** `WRITE_EXTERNAL_STORAGE` scoped to `maxSdkVersion="28"` (CameraX MediaStore output on API ≤28 only). Captured audio is saved/transcribed **entirely on-device** (sherpa-onnx) and **never transmitted** — the only network touch is a one-time recognition-model download. So the mapping is unchanged: **Audio (files/voice/music) = Not collected**, on the same on-device-only basis as the camera. No new sharing relationship — Section C unchanged. privacy.md §2.14 + §3 + the walkthrough §IV **Audio** row and `RECORD_AUDIO` permission row document the posture; when a Play reviewer sees `RECORD_AUDIO`, point them at the on-device-only justification.

_Code refs: `core-sync/.../client/InstantClient.kt` (signOut=token revoke), `feature/.../sync/SyncAuthViewModel.kt` (signOut vs purgeRemoteData #1248), `core-sync/.../coordinator/SyncCoordinator.kt` (purgeRemoteData loops all syncers), `core-sync/.../client/HttpInstantBackend.kt` (admin/transact delete), `core-sync/.../di/SyncModule.kt` (8 syncer domains)._
