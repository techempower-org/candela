---
layout: default
title: Candela · Delete Your Data
description: How to delete your Candela account and synced data — step by step, what's removed, what's kept, and retention.
permalink: /delete-account/
---

# Delete Your Candela Data

**App:** Candela (`org.techempower.candela`)
**Developer:** TechEMPOWER (501(c)(3) nonprofit)
**Contact:** jp@techempower.org

Candela is offline-first — by default nothing leaves your device, so for most
people "deletion" is just uninstalling the app. If you turned on **optional
cloud sync**, here's how to remove that data too.

## Delete your synced data (and keep your account)

If you enabled cloud sync, your library and reading data are stored on our sync
backend (InstantDB). To erase all of it while keeping your account:

1. Open **Candela**.
2. Go to the **sync / account settings** screen.
3. Tap **"Delete cloud data."**

This **immediately** purges everything you've synced — library, reading
positions, follows, bookmarks, highlights, reading notes, pronunciation
dictionary, settings, and your end-to-end-encrypted API keys — from the sync
backend. Your email sign-in remains, so you can keep using sync afterward.

## Delete your account entirely

1. Tap **"Delete cloud data"** (above) to purge your synced data.
2. **Sign out of sync** — this revokes your auth token and wipes the local
   session. (Signing out alone does *not* delete the account record.)
3. To delete your account **record** as well, email **jp@techempower.org** from
   your account address. We complete deletion requests within **30 days**
   (usually much sooner).

## Delete on-device data

Everything Candela stores locally — cache, downloaded voices, Voice Notes
recordings and their transcripts (`notes.db`), the encrypted documents wallet,
WebView cookies, and BYOK tokens — lives **only on your device**. Uninstalling
Candela deletes all of it. You can also clear it via **Android Settings → Apps
→ Candela → Storage → Clear data**.

## What's deleted vs. kept

| Data | On "Delete cloud data" | On uninstall | Retention |
|---|---|---|---|
| Synced library, reading state, annotations, settings | Deleted immediately from the backend | Local copy removed | None kept after deletion |
| End-to-end-encrypted API keys / tokens | Deleted immediately | Removed | None kept |
| Email / account record | Kept until you sign out **and** request deletion | Kept | Removed on request, within 30 days |
| On-device cache, voices, Voice Notes, documents wallet | — | Deleted | Device-local only; never uploaded |

Candela keeps **no** analytics, advertising, or tracking data — it ships none.
Full detail is in our [Privacy Policy](/privacy/).
