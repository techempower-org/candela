# Voice Notes

**(a) What exists.** Voice notes (#1657):
- Record from the mic, with on-device transcription and an optional AI summary.
- **Typed notes** (the pencil), each with a Title, Notes and Tags.
- A searchable list (`notes`), a record screen (`notes/record`) and a detail screen (`notes/detail/{id}`) with share and export (the text is localized, #1818).

Notes stay on the device.

**(b) How a user reaches it.** The **Voice Notes** (waveform) icon in the Library top bar. **Record** is the button at the bottom right; the **pencil** at the top right makes a typed note.

**(c) How to drive it.**
```bash
$V nav notes && $V shot notes.png
$V tap "New note" && $V tap Title && $V type "Verify skill check"
$V tap Notes && $V type "typed from adb, it's fine" && $V text
$V tap Save                                   # or back twice to discard
```

**(d) What lies.**
- **The Record button isn't in the uiautomator dump** on v1.16.0, although the screenshot shows it. `tap Record` fails with "not on screen". Confirm with `shot`. To reach it, `adb shell input tap` its screenshot position (about 885,1340 on a 1080×1920 screen).
- **The first `key back` only closes the keyboard.** The second one leaves, and an unsaved typed note is **dropped without a prompt**.
- On the Voice Notes screen, the **Playing** tab looks selected in the bottom bar. That's a highlight quirk, not navigation state.
- **Recording on the emulator runs with `-no-audio`**, so there's no mic input. Transcription quality can only be checked on the tablet.
