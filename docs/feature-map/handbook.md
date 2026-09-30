# Handbook

**(a) What exists.** *The Candela Handbook*, the user guide as a built-in, narrated book (`source-handbook`, `CandelaHandbookSource`, fiction id `handbook:guide`). Its chapters are generated from `docs/*.md` by `scripts/build-handbook-assets.py`, and a drift guard pins the assets. It works offline. The v1.16.0 chapters are: Getting Started, Voices, The Reader & Accessibility, Sync & Privacy, Connecting reddit, Connecting Google Drive, Connecting Notion, and more.

**(b) How a user reaches it.** **Settings → About → Read the handbook**.

**(c) How to drive it.**
```bash
$V nav handbook && $V text           # the chapter list
$V tap "Chapter 1" --contains        # open a chapter (plays through the reader, see reader-playback.md)
```

**(d) What lies.**
- Chapter rows expose **only a `content-desc`** ("Chapter 1, Getting Started, "), with no `text`. `text` shows them in `[brackets]`, and `tap` matches either.
- **Chapter 4 is still titled "Sync & Privacy"** in v1.16.0, although sync was removed (#1821). The title is stale, not a bug in the page: #1827.
- The Handbook is **generated at build time**. Editing `docs/` changes nothing on a device until a new release ships.
