# Candela feature map

One short page per area. Each page answers the same four questions: **what exists**, **how a user reaches it**, **how to drive it** with the [`candela-verify`](../../skills/candela-verify/SKILL.md) skill, and **what lies** (false errors and misleading signals).

The pages are listed below in **smoke-test order**. Run them top to bottom against a fresh install: each step assumes the one before it passed. Everything here was checked on **v1.16.0** (versionCode 279) on the `candela-api29` emulator.

| # | Area | Smoke step (`V=skills/candela-verify/verify.sh`) | Pass when |
|---|---|---|---|
| 0 | Device | `$V emu-boot && $V install v1.16.0` | `versionName=1.16.0` |
| 1 | Launch | `$V smoke shots/` | `health` exits 0 and `home.png` shows Library |
| 2 | [Settings](settings.md) | `$V nav settings && $V text` | "Pick a section to configure." |
| 3 | [Handbook](handbook.md) | `$V nav handbook && $V text` | "The Candela Handbook" and chapter rows |
| 4 | [TechEMPOWER](techempower.md) | `$V nav techempower && $V text` | "Free help — read out loud." |
| 5 | [Sources / Browse](sources-browse.md) | `$V nav browse && $V tap "Browse all sources" && $V text` | "37 sources to explore" |
| 6 | [Voice Notes](voice-notes.md) | `$V nav notes && $V tap "New note" && $V tap Title && $V type hello` | "hello" shows in `text` |
| 7 | [Morning Briefing / For you](briefing-for-you.md) | `$V nav briefing && $V text`, then `$V nav feed && $V text` | "Build & play my briefing" / "For you" |
| 8 | [Reader / TTS playback](reader-playback.md) | `$V nav library && $V tap Resume --nth 2` | the tablet plays audio (the emulator isn't enough) |
| 9 | Crash gate | `$V health` | still 0 FATAL after all of the above |
| — | Teardown | `$V emu-kill` | the PID is gone |

Pages for other areas get added as they're verified. When you add one, keep the four headings and mark anything you didn't check yourself.
