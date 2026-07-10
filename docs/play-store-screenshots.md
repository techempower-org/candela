# Play Store screenshot capture plan

Capture plan for the Candela Play Store listing. **Capture is blocked on phone access** (luna-1235 has the Z Flip3 for the backend sweep) — this is the plan to execute in one pass once the phone is free.

## Constraints (Play Store)

- **Per device type:** min 2, max 8 screenshots.
- **Aspect ratio:** between 1:2 and 2:1; the listing target is **9:16 portrait**.
- **Format:** PNG or JPG, ≤ 8 MB each.
- **Device:** phone — **Z Flip3, native 1080×2640 (9:22)**.
  - ⚠️ 9:22 (0.41) is **taller than the 1:2 (0.50) floor**, so a raw screencap is **out of spec**. **Crop each capture to 1080×1920 (9:16)** — crop equally top/bottom, preserving the main content (keep the bottom nav bar where it's part of the story; the status bar can stay or be cropped). 1080×2160 (exactly 1:2) is an alternative if a shot needs more vertical room.
  - Tablet set is **optional** (we only have the phone). The app is tablet-adaptive (5-column library), so a tablet set showing the multi-column layout is a strong future add — flagged, not in this pass.

## Capture method (per shot)

```bash
SERIAL=<z-flip3-serial>     # the Z Flip3 (see scripts/phone-check.sh device list)
# Optional: clean status bar via demo mode
adb -s "$SERIAL" shell settings put global sysui_demo_allowed 1
adb -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command enter
adb -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
adb -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false

adb -s "$SERIAL" exec-out screencap -p > raw/01-now-playing.png
# Crop 1080x2640 → 1080x1920 (centered vertical crop, keeps the focal UI)
sips -c 1920 1080 raw/01-now-playing.png --out store/01-now-playing.png   # macOS
# or: magick raw/01-now-playing.png -gravity center -crop 1080x1920+0+0 +repage store/01-now-playing.png

# Exit demo mode when done:
adb -s "$SERIAL" shell am broadcast -a com.android.systemui.demo -e command exit
```

App package: `org.techempower.candela`. Disable any debug overlay (Settings → Developer → Debug) before capturing.

## Pre-staging

Before capture, set up real, attractive content so shots look populated:
- Library seeded with ~6–8 fictions that have **cover art** (Royal Road / Standard Ebooks have good covers).
- One fiction **downloaded + mid-playback** (for the Now Playing + Reader shots).
- A per-fiction **AI chat** with a real Q&A exchange.
- A voice **starred** (so the Voice Library shows the favorites surface).
- Light theme is parchment, dark is brass-on-warm-dark — capture in **dark (Library Nocturne)**; it's the signature look. (Optionally one light-mode shot.)

## Screenshots (recommended Play Store order)

Order leads with the core value (it plays books as audiobooks), then breadth, then depth/differentiators.

| # | Screen | Route / nav path | What to show |
|---|--------|------------------|--------------|
| 1 | **Now Playing (hero)** | `audiobook/{id}/{ch}` (`AudiobookView`) — tap a playing book / Playing tab | Chapter cover, brass circular scrubber mid-progress, transport, the brass voice-settings sparkle. The "turns text into a narrated audiobook" promise. **Lead shot.** |
| 2 | **Reader — read-along** | `reader/{id}/{ch}` (`ReaderView`) — swipe from Now Playing to reader, playing | Chapter body with the **current sentence highlighted in brass** as it's spoken. Shows the read-along highlight + EB Garamond typography. |
| 3 | **Library** | `library` (`LibraryScreen`), Library tab | Grid of book covers + the **Reading / Read / Wishlist** shelf chips + sort. "Your whole library." |
| 4 | **Browse — 34 sources** | `browse` (`BrowseScreen`), Browse tab | A source browse grid (e.g. Royal Road with the filter row) **or** the Plugins source grid showing the breadth (Royal Road, AO3, Gutenberg, Wikipedia, RSS, …). Conveys "34 sources." |
| 5 | **Voice Library** | `settings/voices` (`VoiceLibraryScreen`), Voices tab | Engine-grouped voice list (Piper / Kokoro / Kitten / Supertonic), tier + flag chips, a ⭐ starred voice. "20+ neural voices, on-device." |
| 6 | **Fiction detail + chapters** | `fiction/{id}` (`FictionDetailScreen`) — tap a book | Cover, blurb, **chapter list**, shelf actions. Depth per book. |
| 7 | **AI chat per fiction** | per-fiction chat (from Fiction detail / reader) | A real Q&A ("Who is X?") with an AI answer, ideally a **function-call card** ("Added to Reading shelf"). A differentiator most audiobook apps lack. |
| 8 | **Settings hub** | `settings/hub` (`SettingsHubScreen`), Settings tab | The brass-edged section card grid (Voice & Playback, Reading, Performance, AI, Plugins, …). Shows polish + configurability. |

**Strong alternates** (swap in if one above is weak on the device):
- **Voice Notes** — the Notes surface (Notes pill on the tablet rail; the waveform action in the Library top bar on phones): a note showing the on-device transcript + the consent-gated AI summary (title + key points). **New headline feature in v1.13.0 — strongly consider swapping this in for shot 6 or 7** so the store shows Voice Notes. Pre-stage one recorded note with a transcript and a generated summary.
- **Listening statistics** — `stats` (`StatsScreen`): streaks, time, per-fiction charts (a polished v1.3.0 feature).
- **Voice & Playback settings** — `settings/...` (`VoiceAndPlaybackSettingsScreen`): speed/pitch + the new **Auto-detect language** toggle (v1.3.0).

If trimming to fewer than 8, the minimum compelling set is **1, 2, 3, 4, 5** (hero, read-along, library, breadth, voices).

## Execution checklist (once phone is free)

1. Confirm Z Flip3 serial + `org.techempower.candela` (v1.13.0) installed.
2. Pre-stage content (above); dark theme; debug overlay off.
3. Enter status-bar demo mode.
4. Capture shots 1–8 (raw 1080×2640).
5. Crop each to 1080×1920 (9:16); sanity-check ≤ 8 MB + nothing sensitive on screen.
6. Drop into `docs/play-store/screenshots/` (or wherever the listing assets live) named `01-…`…`08-…`.
7. Exit demo mode.
