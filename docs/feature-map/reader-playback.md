# Reader / TTS playback

**(a) What exists.**
- **Playing tab**: the now-playing screen, with cover art, title and author, the chapter, and the transport below the fold.
- **Reader** (`reader/{fictionId}/{chapterId}`): the text pane, a passive view of `PlaybackController` (the route does *not* auto-load).
- **Engine**: `EnginePlayer` (core-playback). It runs local voice engines (Piper, Kokoro and others via `@VoicePlugin`) plus BYOK Azure HD cloud voices, with a PCM cache, sleep timer, speed control and a MediaSession, so Bluetooth keys, the lock screen and Android Auto work.
- **Reader extras**: teleprompter, Practice mode, voice-paced reading and Ask AI.

**(b) How a user reaches it.** Tap **Resume** on a Library card, or open a book and pick a chapter. Then use the **Playing** tab. **Voices** is where you install and switch voices.

**(c) How to drive it.**
```bash
$V nav library && $V tap Resume --nth 2      # 2nd "Resume" is the button, 1st is the caption
$V wait "Chapter" 20 && $V nav playing && $V shot playing.png
adb -s "$SERIAL" shell dumpsys media_session | grep 'state=PlaybackState'   # sample twice, 5 s apart
$V nav voices && $V text                     # the installed and active voice
```

**(d) What lies.**
- **The emulator doesn't prove TTS.** On v1.16.0, with the Piper Lessac voice installed and active, the API29 AVD showed "Couldn't start this chapter. The voice engine is taking longer than expected." and the MediaSession stayed `state=0`. The cause wasn't logged (see the next point). **Verify audio on the tablet (R83W80CAFZB).**
- **Release builds strip `Log.v/d/i`** (#1276). `EnginePlayer` breadcrumbs at those levels are invisible. Silence in logcat means nothing; only `Log.w/e` survive.
- **One MediaSession read isn't proof.** `state=0` right after a tap may just be loading. Sample it twice and look at the screen, which may show a spinner, the "Couldn't start" card, or playing.
- **`tap Resume` without `--nth 2` does nothing**, because it hits the card's "Resume" caption.
- **A voice swap rebuilds the whole pipeline** (about 30 s). Don't read a slow first chapter after switching voices as a regression.
- The **hourglass badge** on a chapter means a Partial cache that never fills (perma-MISS). That's a known signal, not an engine failure.
- **Not verified:** actual audio output, the sleep timer, and the speed control.
