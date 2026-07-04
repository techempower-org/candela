# Candela FAQ

## Does Candela read files from Google Drive?

**Yes — two ways, and the first needs zero setup.**

1. **Open any single file with the system picker (works today, no account
   connect).** Candela reads EPUB / PDF / TXT / ODT through Android's
   **Storage Access Framework**, and the system document picker exposes Google
   Drive (and Dropbox, OneDrive, etc.) as a *documents provider*. So "Open
   with → Candela" on a Drive file, or picking one from Candela's import
   picker, already imports it into your library — the file streams down
   through the OS, Candela never talks to Google. This is the simplest path
   and covers the everyday "read this book off my Drive" case.

2. **Connect Google Drive as a folder-as-library source
   ([#1496](https://github.com/techempower-org/candela/issues/1496)).** For a
   *browsable* library — whole authorized folders, with **Google Docs read
   natively** (the one thing the system picker can't do: a native Doc has no
   downloadable file, only an export) — connect your Google account in
   Browse. Candela uses the narrow **`drive.file`** scope, so it only ever
   sees the folders/files you explicitly grant through Google's own picker —
   never your whole Drive. Setup + the scope rationale: **docs/google-drive-setup.md**.

## What about Google Keep?

**Not supported (`wontfix`).** Keep's official API is Workspace-enterprise
only (service account + domain-wide delegation); there is no consumer OAuth
scope, and the unofficial reverse-engineered routes are fragile and risk
account-security flags. Instead, share a Keep note into Candela with the
Android **share sheet** (Keep → Share → Candela, `text/plain`), or use the
first-class Notion / Outline / MemPalace sources.
