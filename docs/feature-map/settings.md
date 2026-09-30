# Settings

**(a) What exists.** A **hub** of section rows (`SettingsHubScreen`) replaced the old long page (#440). The sections are:
- Voice & Playback, Voice library, Cloud Voices, Pronunciation dictionary;
- Reading, Appearance, Accessibility, Performance;
- AI, AI sessions, Plugins, Content Sources, Downloads & Storage, Notifications;
- Account (Royal Road, AO3 and GitHub sign-ins; there has been no cloud sync since #1821);
- Morning Briefing, For you feed, Scripts, Benefits, Listening stats;
- Advanced, Developer, About.

A **Search settings** field searches every setting (`SettingsSearchIndex`). **All settings** is the legacy one-page view, kept on purpose as an escape hatch.

**(b) How a user reaches it.** The **Settings** tab on the bottom bar.

**(c) How to drive it.**
```bash
$V nav settings && $V text                 # the hub
$V tap "Voice & Playback" && $V text       # a section (tap scrolls to find it)
$V tap "Search settings" && $V type speed  # typing works; the filtered results are NOT verified
```

**(d) What lies.**
- The hub **restores its old scroll position**, so a row can be above the viewport. `tap` scrolls down first, then up, to find it.
- Section **titles repeat** in the subtitle `content-desc` ("Voice & Playback, Voice, speed, …"). An exact `tap "Voice & Playback"` hits the title, which is fine. With `--contains`, a first match on a longer label is also possible.
- **`key back` from the hub root leaves Candela** (the launcher comes to the front). `nav` recovers without a cold start; a bare `tap` doesn't.
- **In landscape the hub is longer** (fewer rows per screen). `tap` scrolls up to 8 times each way; raise `CANDELA_VERIFY_SCROLLS` if a row is still out of reach.
- **Not verified:** that Settings search filters. On v1.16.0 the hub still showed its sections after typing "speed". Check it by eye with a `shot` before trusting it either way.
- The **resumed activity** is `MainActivity` on every screen. It doesn't show which settings page is open; `text` does.
