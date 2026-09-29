---
layout: default
title: Candela · Delete Your Data
description: How to delete your Candela data. Candela has no account and no cloud sync; everything lives on your device.
permalink: /delete-account/
---

# Delete Your Candela Data

**App:** Candela (`org.techempower.candela`)
**Developer:** TechEMPOWER (501(c)(3) nonprofit)
**Contact:** jp@techempower.org

**Candela has no account and no cloud sync.** There is nothing to sign up
for and no Candela server holds your data, so deleting your data means
deleting it from your device.

## Delete everything on your device

Everything Candela stores (library, reading positions, bookmarks, highlights,
notes, settings, cache, downloaded voices, Voice Notes recordings and their
transcripts, the encrypted documents wallet, WebView cookies and BYOK tokens)
lives **only on your device**. To delete all of it, either:

- **Uninstall Candela**, or
- open **Android Settings → Apps → Candela → Storage → Clear data**.

## Used cloud sync in an older version?

Before 2026-09-29, Candela offered optional cloud sync through InstantDB
(email + magic code). That feature has been **removed**. The app no longer
sends anything to InstantDB and there is no in-app deletion button any more.

If you signed in to sync in an earlier version and want your old sync record
deleted, email **jp@techempower.org** from the address you signed in with. We
complete deletion requests within **30 days** (usually much sooner).

## What's deleted vs. kept

| Data | On uninstall / Clear data | Retention |
|---|---|---|
| Library, reading state, annotations, notes, settings | Deleted | Device-local only; never uploaded |
| API keys / tokens, documents wallet, Voice Notes | Deleted | Device-local only; never uploaded |
| Old sync record (earlier versions only) | — | Deleted on request, within 30 days |

Android's own device backup (under **your** Google account, controlled in
Android Settings) may keep a copy of Candela's library and settings for
restoring onto a new phone. Remove it from Android's backup settings if you
wish; it never includes Candela's keys, documents wallet or Voice Notes.

Candela keeps **no** analytics, advertising, or tracking data; it ships none.
Full detail is in our [Privacy Policy](/privacy/).
