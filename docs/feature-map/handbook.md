# Handbook

**(a) What exists.** *The Candela Handbook*, the user guide as a built-in, narrated book (`source-handbook`, `CandelaHandbookSource`, fiction id `handbook:guide`). Its chapters are generated from `docs/*.md` by `scripts/build-handbook-assets.py`, and a drift guard pins the assets. It works offline. The chapters are: Getting Started, Voices, The Reader & Accessibility, Privacy (v1.16.0 still says "Sync & Privacy"; fixed in #1827), Connecting reddit, Connecting Google Drive, Connecting Notion, and Frequently Asked Questions.

**(b) How a user reaches it.** **Settings → About → Read the handbook**.

**(c) How to drive it.**
```bash
$V nav handbook && $V text           # the chapter list
$V tap "Chapter 1" --contains        # open a chapter (plays through the reader, see reader-playback.md)
```

**(d) What lies.**
- Chapter rows expose **only a `content-desc`** ("Chapter 1, Getting Started, "), with no `text`. `text` shows them in `[brackets]`, and `tap` matches either.
- **On v1.16.0, chapter 4 is still titled "Sync & Privacy"**, although sync was removed (#1821). It's renamed "Privacy" from the next release (#1827). The chapter id `sync-and-privacy` is unchanged, so saved positions still resolve.
- **The drift guard isn't wired into CI.** `scripts/build-handbook-assets.py --check` exists, but no workflow runs it: on v1.16.0 the manifest was still stamped 1.15.2. Run `--check` yourself after editing `docs/`.
- The Handbook is **generated at build time**. Editing `docs/` changes nothing on a device until a new release ships.
