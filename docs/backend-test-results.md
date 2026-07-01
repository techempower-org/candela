# Candela — Backend Source Test Results

**Device:** Samsung Galaxy Z Flip3 (SM-F711U), serial `R5CRB0W66MK`
**Build:** Candela **v1.4.5** · versionCode **247** _(verified via `dumpsys package org.techempower.candela`)_ · _historical record of a v1.4.5 test run; current release is now v1.6.3 / vc257_
**App id:** `org.techempower.candela` · launcher `in.jphe.storyvox.MainActivity`
**Screen:** 1080×2640 @ 480dpi · **Date:** 2026-06-29 · **Active voice during test:** Azure "Adam Multilingual"

## Executive summary
**12 free/no-auth sources verified hands-on; all 3 playback pipelines confirmed; 1 logged issue (#1354) + minor notes.** Browse + open exercised per source via `uiautomator`-bounds navigation; playback verified per *pipeline* (every text source shares one TTS path) plus a real play on each distinct audio path.

- ✅ **Playback pipelines proven:** TTS-text (Gutenberg, audible, `state=PLAYING`), live-stream (Radio/KVMR, ExoPlayer + 44.1 kHz to speaker), pre-recorded MP3 (LibriVox, `.mp3` via Media3).
- ✅ **Google News — cleared on recheck:** first open momentarily showed "0 ch", but a re-open rendered real headlines (Tom's Hardware, an IOCCC29 entry, XDA…). The "0 ch" was a **transient lazy-load** before the feed populated, not a bug. Minor: the count metadata can read 0 for a beat on first open.
- ⚠️ **Finding — re2 regex error** (`'\p{Letter}'` rule fails to compile) logs on chapter open; non-fatal. **Filed as #1354.**
- 🧭 **UX note:** the Playing/reader screen (HybridReaderShell) swallows bottom-nav taps — Back returns to Browse; `keyevent 127` / `media dispatch pause` controls playback.

## Test harness notes
- Screenshots: `screencap -p /sdcard/c.png && adb pull` — `exec-out screencap` is corrupted by a Z-Flip3 "Multiple displays" warning prepended to the PNG.
- `uiautomator dump` **works** here (it doesn't on Waydroid) → navigation by exact element bounds.
- Source selection = **tap the chip** (scrolling the chip row only reveals; it doesn't select). Returning to Browse resets the row to the selected source at x≈84, so chip coords must be re-dumped each time.

---

## Results — sources verified hands-on

| Source | id | Browse | Open | Play | Notes |
|--------|----|:------:|:----:|:----:|-------|
| Project Gutenberg | `gutenberg` | ✅ | ✅ | ✅ | Moby Dick → playing 0:06/143:36; TTS pipeline proof |
| Radio | `radio` | ✅ | ✅ | ✅ | KVMR 89.3 LIVE; ExoPlayer stream → speaker; live-stream proof |
| LibriVox | `librivox` | ✅ | ✅ | ✅ | Count of Monte Cristo; `.mp3` via Media3; MP3 proof |
| Standard Ebooks | `standardebooks` | ✅ | ✅ | ⟳ | Frankenstein, Count of Monte Cristo |
| Wikipedia | `wikipedia` | ✅ | ✅ | ⟳ | Morris Park Aerodrome, 2026 FIFA World Cup |
| Wikisource | `wikisource` | ✅ | ✅ | ⟳ | work cards → "by Wikisource" detail |
| arXiv | `arxiv` | ✅ | ✅ | ⟳ | cs.AI papers (DexCompose, Nash Equilibrium) |
| PLOS | `plos` | ✅ | ✅ | ⟳ | real open-science articles |
| Hacker News | `hackernews` | ✅ | ✅ | ⟳ | front-page stories → detail |
| GitHub | `github` | ✅ | ✅ | ⟳ | "The Cartographer's Lantern" test repo |
| Notion (TechEmpower) | `notion-techempower` | ✅ | — | ⟳ | Guides/Resources/About/Donate (default landing) |
| Google News | `googlenews` | ✅ | ✅ | ⟳ | content renders on recheck (real headlines); initial "0 ch" was transient feed lazy-load |
| Readability | `readability` | ◐ | — | — | landing + instructions render; `+` paste flow not driven via adb |

**Legend:** ✅ pass · ⚠️ caveat · ◐ partial · ⟳ play verified via shared pipeline (TTS proven on Gutenberg; all text sources use the identical path) · — not exercised.

> **Why "play via pipeline" for the text sources:** playback is shared infrastructure keyed by content type. A source's own job is to deliver the catalog + chapter text/URL — which browse+open exercises. The three distinct *playback* paths (TTS, live-stream, MP3) were each proven with a real audible play (Gutenberg/Radio/LibriVox). Re-playing every text source would re-test the same TTS path, so those are marked pipeline-verified rather than individually played. The one anomaly (Google News 0-ch) is a *content/feed* issue caught at the open stage, which is exactly why open was exercised per-source.

---

## Not exercised — input-required (enabled, same `+`/paste pattern as Readability)
| Source | id | Why |
|--------|----|-----|
| RSS | `rss` | needs a typed feed URL |
| Make-your-own (MyAudiobook) | `myaudiobook` | needs typed/pasted text in the Create flow |

Readability/RSS/MyAudiobook all share the "tap +, paste/type, submit" entry. The Readability landing renders correctly; driving the paste dialog + typing a URL with special chars (`:` `/`) via `adb input text` is fragile and wasn't completed. **Recommend a manual paste test** for these three.

## Blocked — cannot meaningfully test via adb
| Source | id | Blocker |
|--------|----|---------|
| EPUB | `epub` | SAF folder picker (system file UI) |
| PDF | `pdf` | SAF folder picker |
| OCR ("Scanned text") | `ocr` | live camera / image capture |
| Discord | `discord` | no bot token in vault (personal logins only; ToS bans user tokens) |
| Slack | `slack` | no `xoxb-` bot token (workspace logins + release webhook only) |
| Matrix | `matrix` | no access token (vault "matrix" = NZBMatrix, unrelated) |
| Telegram | `telegram` | no BotFather token in vault |
| Notion (PAT) | `notion-pat` | maybe — "submissions intake" vault item has custom fields; not attempted |
| Outline | `outline` | "Outline API Key (realm-portal)" exists; needs host+token config + LAN reach |
| MemPalace | `mempalace` | only DB creds in vault; source reads `familiar:8085` over LAN — needs config + LAN |
| Palace / Palace Library | `palace` | needs a user-supplied OPDS library URL before it returns anything |

## Done elsewhere / skipped
- **Royal Road** (`royalroad`) — verified by drift-runner ✅
- **AO3** (`ao3`), **Bookshare** (`bookshare`) — skipped, JP has no account (Bookshare scaffold returns `AuthRequired` regardless)

## Not FictionSource (correctly out of scope)
`source-azure` (cloud-voice backend) · `source-epub-writer`, `source-audiobook-writer` (export writers).

---

## Findings detail
1. **Google News "0 ch" — cleared on recheck.** First open of "Top stories" showed *"by Google News · 0 ch · Ongoing"* with no headlines, but re-opening rendered the real feed (Tom's Hardware, an IOCCC29 "Pong recompiles itself" entry, XDA…). The empty count was a transient lazy-load before the feed populated — **not a bug**. Minor polish opportunity only: the "N ch" count can read 0 for a beat on first open before the feed resolves.
2. **re2 regex compile error (→ #1354).** `E native re2.cc Error parsing '^|[^\p{Letter}\\]': invalid character class range: \p{Letter}` on chapter open — a TTS text-normalization replacement rule fails to compile under this re2 build. Non-fatal (playback works), but it's a real logged error on every chapter open.
3. **HybridReaderShell nav (UX note).** From the Playing/reader screen, bottom-nav taps (Browse/Library) don't switch tabs; system Back unwinds to Browse. Consistent with the passive-reader architecture; flagging in case the nav bar should remain live there.
