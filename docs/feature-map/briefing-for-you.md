# Morning Briefing / For you

**(a) What exists.**
- **Morning Briefing** (`morning_briefing`): one continuous narrated episode built from sources you pick (Google News headlines, Hacker News, arXiv papers, your RSS feeds). Each source has **Fewer/More items** steppers (default 3). **Build & play my briefing** starts it, and it plays top to bottom.
- **For you** (`feed`, #1675): a feed of new posts from everything you follow, newest first. Tapping an item starts playback that continues down the feed (**Stop auto-play**), and there is a **Refresh feed** button.

**(b) How a user reaches it.** **Settings → Morning Briefing** and **Settings → For you feed**.

**(c) How to drive it.**
```bash
$V nav briefing && $V text
$V tap "More items from Hacker News" && $V text | grep items   # stepper
$V tap "Build & play my briefing"                              # network + TTS
$V nav feed && $V text                                         # "Nothing new yet…" on a fresh install
```

**(d) What lies.**
- **A fresh install has an empty For you feed** ("Nothing new yet. Follow a website…"). That's correct, not broken: follow something first.
- **Build & play needs the network and a working voice engine.** On the emulator the voice engine is the weak link (see [reader-playback.md](reader-playback.md)). Test real playback on the tablet.
- Stepper buttons have **only a `content-desc`**; the count is a separate `text` node ("3 items").
- **Not verified:** building and playing a briefing end to end.
