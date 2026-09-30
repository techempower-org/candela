---
name: candela-verify
description: Drive Candela on the API29 emulator or the tablet over adb. Cold-start it, health-check it, navigate to a named screen, tap and type, take screenshots and read the on-screen text. Use it to prove a released build works, or to reproduce a bug on a device. It never builds anything.
---

# candela-verify

One script, `skills/candela-verify/verify.sh`, with one verb per call. Every verb exits non-zero on failure, so you can chain verbs with `&&` and use the chain as a check. `docs/feature-map/` lists what to drive for each area, and `docs/feature-map/README.md` gives the order to run them in as a smoke test.

## Quick start (emulator)

```bash
V=skills/candela-verify/verify.sh
$V emu-boot                # headless candela-api29 on :5580; writes a PID file
$V install v1.16.0         # released APK via `gh release download` (omit the tag for the latest)
$V smoke /tmp/shots        # cold start + health + home.png
$V nav settings && $V tap About && $V tap "Read the handbook" && $V text
$V shot out.png
$V emu-kill                # kills by PID, never by pattern
```

## Verbs

| Verb | What it does |
|---|---|
| `emu-boot` / `emu-kill` | Boot the AVD `candela-api29` headless (`-no-window -no-audio -gpu swiftshader_indirect`). Kill it by PID from `~/.cache/candela-verify/emu.pid`. |
| `install [vX.Y.Z\|file.apk]` | Install a released APK, cached in `~/.cache/candela-verify/`, then print `versionName`. |
| `start` | Clear the crash buffer, force-stop, run `am start -W`, then wait up to 10 s for a pid. |
| `health` | Check for a pid, 0 `FATAL EXCEPTION` in `logcat -b crash`, and Candela as the resumed activity. Fails if any is false. |
| `nav <screen>` | Go to `playing`, `library`/`home`, `browse`, `voices`, `settings`, `techempower`, `handbook`, `notes`, `briefing` or `feed`. It first brings Candela to the front (no cold start) if something else is in front, then backs out of full-screen pages until the bottom bar shows. |
| `tap <label> [--contains] [--nth N]` | Tap the Nth node whose text or content-desc matches. An exact match beats `--contains`. Scrolls down, then up, to find it. |
| `wait <label> [secs]` | Poll until the label appears (substring match). |
| `type <text>` | Type into the focused field. Refuses `%` and `#`, which `input text` mangles. |
| `key <back\|home\|enter\|KEYCODE_…>` | Send a key event. |
| `scroll [down\|up]` | Swipe once. |
| `text` | Print the on-screen text from a `uiautomator dump`: `text \| [content-desc]`, one node per line. |
| `shot <file.png>` | `exec-out screencap -p`, then check the PNG signature. |
| `stop` | Force-stop the app. |

## Rules

- **Device choice:** `$CANDELA_SERIAL` wins, then `emulator-5580`, then the tablet `R83W80CAFZB`. If the tablet is on Wi-Fi debugging with a bare `ip:port` serial, set `CANDELA_SERIAL`.
- **Never** run `adb usb` or `adb tcpip`, and never toggle Wireless debugging: it turns off on the tablet, and only a person can turn it back on.
- **Never build on katana.** Install a release (`install vX.Y.Z`) or a CI artifact APK (`install path.apk`). An unreleased change goes through CI, not a local gradle run.
- **Kill the emulator by PID** (`emu-kill`). A `pkill -f emulator` also matches the calling shell.
- **Personal phone R5CR80DJ7TR:** passive adb only (no taps or installs).

## What lies (read before trusting a result)

- **A missing label in `text` doesn't mean the element is absent.** On v1.16.0 the Voice Notes **Record** button is visible in the screenshot but missing from the dump. Take a `shot` too before concluding anything.
- **Duplicate labels:** Library has two "Resume" nodes. The first is the card caption, and tapping it does nothing. Use `tap Resume --nth 2`.
- **The first `key back` in a text field only closes the keyboard.** A second `back` leaves the screen, and an unsaved note is dropped without a prompt.
- **Release builds strip `Log.v/d/i`** (#1276). "No log lines" proves nothing; only `Log.w/e` survive.
- **One `dumpsys media_session` read isn't proof.** `state=0` right after a tap can just be the engine loading. Sample twice, some seconds apart, and look at the screen.
- **TTS on the emulator:** on v1.16.0 the API29 AVD showed "Couldn't start this chapter. The voice engine is taking longer than expected" for the Guides book, with the Piper Lessac voice installed and active. Verify audio on the tablet.
- **`GooglePlayServicesUtil … Play Store missing`** warnings are emulator-image noise (the image has no Play Store), not a Candela bug.
- **Waydroid's uiautomator is broken:** `text` there fails with an empty dump. Use the emulator or the tablet.
- **Landscape swaps the bottom bar for a nav rail** that adds a **Notes** entry, and its labels wrap mid-word (#1828). `nav` still works. `scroll` takes its size from the live uiautomator root, so swipes follow rotation, which `wm size` wouldn't.
- **The resumed activity is always `MainActivity`** (single activity plus Compose navigation). It proves Candela is in front, not which screen is showing. Use `text` for that.

## Proof

`proof/home.png` is a real v1.16.0 (versionCode 279) Home screen captured by `smoke` on the emulator. `proof/smoke.txt` has the `smoke` output (cold start + `health`) that went with it.
