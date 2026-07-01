# Candela

[![Build](https://github.com/techempower-org/candela/actions/workflows/android.yml/badge.svg)](https://github.com/techempower-org/candela/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/techempower-org/candela?color=b88746&label=release)](https://github.com/techempower-org/candela/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-b88746.svg)](LICENSE)
[![Built by dream-team](https://img.shields.io/badge/built%20by-dream--team-7d5fff.svg)](#how-it-was-built)

**Candela is [TechEmpower](https://techempower.org)'s accessible resource app.**
Browse free tech guides, connect with our peer-support Discord, dial 211 for local help — and listen to any of it through a neural-voice audiobook engine that reads everything aloud.

**Under the hood: a neural-voice audiobook player for any text you have.**
Stream chapters from **twenty-seven fiction backends** — [Royal Road](https://royalroad.com), [GitHub](https://github.com/), an [Outline](https://www.getoutline.com) wiki, RSS / Atom feeds, a [Memory Palace](https://github.com/techempower-org/mempalace) you host yourself, local EPUB and PDF files, scanned text via on-device OCR, [Project Gutenberg](https://www.gutenberg.org/), [LibriVox](https://librivox.org/) public-domain audiobooks, [Archive of Our Own](https://archiveofourown.org/), [Standard Ebooks](https://standardebooks.org/), [Wikipedia](https://en.wikipedia.org/), [Wikisource](https://en.wikisource.org/), Radio (with [Radio Browser](https://www.radio-browser.info/) search across 30k+ stations), [Notion](https://notion.so/) pages or databases (defaults to TechEmpower's resource library — Guides, Resources, About, Donate, ~80 free-tech resource entries), [Hacker News](https://news.ycombinator.com/), [arXiv](https://arxiv.org/), [PLOS](https://plos.org/), [Discord](https://discord.com/) channels, [Telegram](https://telegram.org/) channels, [Palace Project](https://thepalaceproject.org/) free-library titles, [Slack](https://slack.com/) channels, [Matrix](https://matrix.org/) rooms, [Bookshare](https://www.bookshare.org/) accessible-library titles, [Google News](https://news.google.com/) topics, and any web article via Readability extraction. Read aloud by an in-process neural TTS engine. A hybrid reader/audiobook view highlights the spoken sentence in brass as you listen. Built for Android phones, tablets, and Wear OS.

> **TechEmpower** is a 501(c)(3) nonprofit closing the digital divide for low-income individuals and families with free tools, training, and software. Candela is built on that mission — the engine identity is consistent (Library Nocturne brass-on-warm-dark theme, same as it's always been), but every user-facing surface leads with TechEmpower's framing now. [techempower.org](https://techempower.org) · [Donate](https://techempower.org/donate) · Peer-support [Discord](https://discord.gg/j3SVttxw7k).

<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/03-reader.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/screenshots/03-reader-light.png">
    <img src="docs/screenshots/03-reader.png" width="320" alt="Candela reader playing The Archmage Coefficient" />
  </picture>
</div>

> **What just shipped** lives on the [latest release](https://github.com/techempower-org/candela/releases/latest) and in the [changelog](CHANGELOG.md) — both always resolve to the current version, so this line never goes stale. Where Candela is now: **twenty-seven fiction backends** behind a plugin-seam architecture (a new backend is ~4 touchpoints — `@SourcePlugin` annotation + KSP-generated Hilt registration); **four in-process neural voice families** (Piper, Kokoro, KittenTTS, Supertonic 3) plus your device's built-in **System TTS** and optional **Azure HD** cloud voices; **AI chat per fiction** with cross-fiction memory, function calling, and multi-modal image input; a **hybrid reader/audiobook view** that highlights the spoken sentence in brass; **Wear OS** and **Android Auto** support; and a **TechEmpower-first** home. GPL-3.0 (downstream of the engine, not a posture choice — see [License](#license)).

---

## What it does

- **Twenty-seven fiction backends, side by side.** Browse [Royal Road](https://royalroad.com) with the full filter set (tags include/exclude, status, type, length, rating, content warnings, sort); browse fiction repos on GitHub via the curated [candela-registry](https://github.com/techempower-org/candela-registry) plus live `/search/repositories` results; subscribe to any **RSS / Atom feed** with a managed suggested-feeds list from [candela-feeds](https://github.com/techempower-org/candela-feeds); pull articles from a self-hosted **[Outline](https://www.getoutline.com)** wiki; mount a **[Memory Palace](https://github.com/techempower-org/mempalace)** you host yourself; open **local EPUB files** from any folder via the system file picker; browse **Project Gutenberg's** 70,000+ public-domain books; pull fanfic from **Archive of Our Own** (per-tag feeds + official EPUBs); read **Standard Ebooks'** hand-curated typographically-polished classics; narrate any **Wikipedia** or **Wikisource** article (heading-split chapters; Wikisource walks multi-part works as `/Subpage` chapters); listen to **Radio** with 5 curated stations (KVMR 89.3, Capital Public Radio, KQED 88.5, KCSB 91.9, SomaFM Groove Salad) plus **Radio Browser API** search across 30,000+ stations worldwide; read a **Notion** database (defaults to the techempower.org content DB — paste an integration token and you're in); narrate **Hacker News** top stories + Ask HN / Show HN threads with comments; listen to **arXiv** abstracts in cs.AI and other categories; read **PLOS** open-access peer-reviewed science papers; or pull serialized fiction from **Discord** channels (channels = fictions, messages = chapters, bot-token auth). Open **local PDF files** and **scanned documents** (on-device OCR turns photos and scans into readable text); browse **LibriVox** public-domain human-narrated audiobooks; pull serialized fiction from **Telegram**, **Slack**, and **Matrix** channels; browse **Palace Project** free-library titles; read **[Bookshare](https://www.bookshare.org/)** accessible-library titles (for readers with print disabilities — a natural fit for Candela's accessibility mission); scan **Google News** topics; and turn **any web article** into a chapter via Readability extraction. Each backend has its own on/off toggle in **Settings → Plugins** (the plugin manager iterates the registry, so adding a new backend is automatic).
- **Plays chapters as audiobooks** through an in-process neural TTS engine. **Four neural voice families ship** — [Piper](https://github.com/rhasspy/piper) (45 voices, ~14–30 MB each), [Kokoro](https://github.com/hexgrad/kokoro) (53 multi-speaker voices, ~330 MB shared model), **KittenTTS** (lightest tier, ~25 MB shared across 8 en_US speakers — designed for slow devices where Piper struggles), and **Supertonic 3** (newest — live since v1.2.3, 10 HD en_US speakers from a shared model) — **plus your device's built-in System TTS** as a zero-download fallback for the lowest-end hardware. Neural voice models download on demand from `voices-v2`; nothing is bundled in the APK. No cloud, no API keys, no per-character billing.
- **Optional cloud voices** — bring-your-own-key [Azure Cognitive Services HD voices](https://learn.microsoft.com/azure/ai-services/speech-service/text-to-speech) for studio-grade narration on slow devices. Offline fallback to the local engine if your key fails or the network drops. Azure is opt-in, never required, never billed by Candela.
- **Tier 3 multi-engine parallel synthesis.** Run 1–8 neural synthesis engine instances side-by-side, each with its own thread pool, so a single sentence's chunks render in parallel and the next sentence is already queued before the current one finishes. Twin sliders in **Settings → Performance** (Engines, Threads/engine) let you tune for your CPU. The producer pins to a dedicated `URGENT_AUDIO` thread to keep audio scheduling honest under load.
- **Auto language detection.** When a passage switches language mid-chapter — French dialogue in an English novel, a Japanese aside — Candela detects it and routes that text to a matching neural voice, then switches back at the next sentence. Opt-in in Settings → Voice & Playback; uses Kokoro's multi-language speakers (en / es / fr / hi / it / ja / pt / zh).
- **Highlights the current sentence** in brass as the engine speaks. Swipe between audiobook view (cover, scrubber, transport) and reader view (chapter text). The highlight glides between sentences to match the read-aloud rhythm.
- **Auto-advances** between chapters. Eager-downloads ahead so the next chapter is ready when the current ends. PCM cache buffering keeps playback smooth when synthesis falls behind — the player pauses, refills, resumes without a glitch.
- **AI chat per fiction, that actually does things.** Per-book chat sessions across seven LLM providers (Claude direct, Anthropic Teams via OAuth, OpenAI, Vertex, Bedrock, Foundry, Ollama) with grounding controls — feed the AI the current sentence, the entire chapter, or the entire book so far. Long-press a word to ask "Who is X?". AI-generated chapter recaps you can read aloud through the same TTS pipeline. **Cross-fiction memory** ([#217](https://github.com/techempower-org/storyvox/issues/217)) — the AI remembers character / place / concept entities across books in your library; per-book **Notebook** tab on Fiction Detail surfaces the entries with manual edit. **Function calling** ([#216](https://github.com/techempower-org/storyvox/issues/216)) — say "Add this book to my Reading shelf", "Queue chapter 5", "Mark this chapter read", "Slow it down to 1.2x", "Open Voice Library", or "Find me cultivation novels on Royal Road" (**AI catalog search** spans all 27 source backends) and the AI invokes the tool; a brass-edged card in the chat stream shows in-flight / success / error state. **Multi-modal image input** ([#215](https://github.com/techempower-org/storyvox/issues/215)) — paste cover art / fanart / scene refs into the chat; the AI sees them (Anthropic + OpenAI native; auto-downscale to 1280px / JPEG q=85 to stay under the API's image-processing ceiling).
- **Plugin manager Settings tab** — a brass-edged card grid in Settings → Plugins, one card per `@SourcePlugin`-annotated backend. Search input, On / Off / All chips, tap-for-details modal. Three category sections: Fiction sources (27 in-tree), Audio streams (Radio), Voice bundles (v2 placeholder). Adding a new backend automatically surfaces a card — no edit to the screen file needed.
- **Magical voice settings icon on the play screen.** Brass soundwave-with-sparkle replaces the buried `⋮` overflow's voice section. Tap → Material 3 bottom sheet with five live-applying rows (Speed / Pitch / Voice picker chip / Sentence silence / Sonic high-quality) + an Advanced expander for per-voice lexicon + Kokoro phonemizer-lang. Long-press → Voice Library.
- **Per-fiction playback speed.** Dial in a speed for one book and Candela remembers it — reopen that fiction and your speed auto-restores instead of snapping back to the global default.
- **Wear OS Library Nocturne.** Brass-on-warm-dark theme on the watch; round watches get a circular brass scrubber wrapping the chapter cover, square watches a brass-tinted linear scrubber.
- **Android Auto.** A media-browser service surfaces your library and Now Playing on the car head unit — browse your shelves and drive playback from the dashboard.
- **GitHub sign-in via OAuth Device Flow** (no API key paste). Lifts the anon 60 req/hr cap to 5,000, unlocks "My Repos" / "Starred" / "Gists" tabs in Browse, and (opt-in) private-repo access for treating private repos as your personal book library.
- **Voice library with tiers and favorites.** Engine-grouped picker, star toggles for the voices you keep coming back to, and a Starred surface that floats them to the top.
- **Sleep timer** with 15/30/45/60-minute presets, an "end of chapter" mode, a countdown pulse as time runs out, and shake-to-extend during the fade-out tail (#150).
- **Smart-resume CTA** — the Library "Resume" button respects your last paused/playing intent so it never auto-plays at you when you opened the app to *read*.
- **Highlights & notes.** Long-press to select text in the reader, pick a colour, and attach a note — highlights persist and sync, and tapping a saved one lets you edit or remove it. **Share quotes** — select a passage and send it to the clipboard or any app with author + title attribution. Plus **find-in-chapter** search with next/previous match, **in-book search** that finds a word across *every* chapter (not just the one you're reading), and **chapter previews** (a body-text snippet in the chapter list).
- **Tap-to-define.** Long-press any word in the reader for an instant [Wiktionary](https://www.wiktionary.org) definition in a bottom sheet — a quick dictionary gloss, separate from the AI's "Who is X?" lookup.
- **Library shelves & sort.** Built-in **Reading / Read / Wishlist** shelves (a book can sit on several at once), and sort the library by title, author, recently added, recently played, or longest-unread.
- **Listening statistics.** A dashboard tracks reading streaks, total listening time, chapters completed, and per-fiction breakdowns, rendered as on-device charts — your stats never leave the phone.
- **Make your own audiobook.** Export any fiction to a chaptered `.m4b` with per-chapter markers, rendered through your chosen neural voice — play it in any audiobook app. EPUB export too.
- **Now-Playing home-screen widget** — play/pause, next, and the sleep timer straight from your launcher (1×1 / 4×1 / 4×2 sizes).
- **Bedtime auto-sleep + Do Not Disturb.** The sleep timer can auto-arm when your phone enters Bedtime mode, and optionally silence notifications while it runs.
- **First-run onboarding** — a three-screen welcome (intro → voice picker → first fiction) gets newcomers listening in under a minute.
- **Library + Follows tabs** with sign-in via WebView (your Royal Road follow list syncs into the app).
- **Infinite-scroll Browse** across every tab.
- **Cheap polling for new chapters.** GitHub-sourced fictions watch the repo's HEAD SHA; the manifest is only re-scanned when something changes — one HTTP request per fiction per check.
- **MediaSession-aware** — lock-screen art, transport from Bluetooth headsets, headphone media buttons, notification shade.
- **Settings redesign** — section hub of brass-edged cards (Voice & Playback, Reading, Performance, AI, Plugins, Account, Memory Palace, About) with brass section icons and a unified component vocabulary. New plugin-manager landing replaces the old long-scroll Library & Sync section.
- **Library Nocturne theme** — brass on warm dark, EB Garamond chapter body, Inter UI. Light mode is parchment cream.
- **Adaptive layouts** — fills the screen on phones (2 columns), tablets (5), foldables (more).

## Screens

<table>
<tr>
<td align="center"><b>Browse</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/01-browse.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/01-browse-light.png"><img src="docs/screenshots/01-browse.png" width="220" alt="Browse" /></picture></td>
<td align="center"><b>Fiction detail</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/02-detail.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/02-detail-light.png"><img src="docs/screenshots/02-detail.png" width="220" alt="Fiction detail" /></picture></td>
<td align="center"><b>Reader / audiobook</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/03-reader.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/03-reader-light.png"><img src="docs/screenshots/03-reader.png" width="220" alt="Reader / audiobook" /></picture></td>
</tr>
<tr>
<td align="center"><b>Library</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/04-library.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/04-library-light.png"><img src="docs/screenshots/04-library.png" width="220" alt="Library" /></picture></td>
<td align="center"><b>Follows</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/07-follows.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/07-follows-light.png"><img src="docs/screenshots/07-follows.png" width="220" alt="Follows" /></picture></td>
<td align="center"><b>Settings</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/05-settings.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/05-settings-light.png"><img src="docs/screenshots/05-settings.png" width="220" alt="Settings" /></picture></td>
</tr>
<tr>
<td align="center"><b>Royal Road filters</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/06-filter-dark.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/06-filter.png"><img src="docs/screenshots/06-filter-dark.png" width="220" alt="Royal Road filters" /></picture></td>
<td align="center"><b>GitHub filters</b><br/><picture><source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/06b-filter-github-dark.png"><source media="(prefers-color-scheme: light)" srcset="docs/screenshots/06b-filter-github.png"><img src="docs/screenshots/06b-filter-github-dark.png" width="220" alt="GitHub filters" /></picture></td>
<td></td>
</tr>
</table>

## TTS engine

Candela links a local neural TTS engine in-process via the [VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) `:engine-lib` AAR (published to JitPack) — the underlying inference library, not a user-facing brand. That AAR re-projects [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) inference plus the Piper, Kokoro, KittenTTS, and Supertonic wrappers into a single dependency. For the neural voices we bypass Android's `TextToSpeech` framework entirely, manage our own `AudioTrack` with a fat buffer, and pipeline next-sentence generation against current playback. No second APK, no install gate, no engine-binding handshake — neural synthesis runs in Candela's own process. For the lowest-end hardware, Candela can also route synthesis to the device's built-in **System TTS** (Android's `TextToSpeech`) as a zero-download fallback.

For users who want studio-grade narration on slow devices, **Azure Cognitive Services HD voices** are wired in as an optional remote backend (BYOK). Add your key and region in **Settings → Voice & Playback → Azure** and pick from the full Azure HD voice roster. If your key fails or the network drops, Candela falls back to your selected local voice for the rest of the chapter — playback never just stops on you.

Voice model weights for the local engine are downloaded on demand by `VoiceManager` from the `voices-v2` GitHub release; the in-app picker shows what's installed and what's available. See [`docs/voices.md`](docs/voices.md) for the catalog and refresh workflow.

## Install (sideload)

Candela is currently distributed by sideloading. CI builds debug APKs on every `main` push; tagged releases (`vX.Y.Z`) attach a signed APK to the GitHub release.

1. Download the latest `candela-v*.apk` from the [latest release](https://github.com/techempower-org/candela/releases/latest).
2. On your Android device, enable **Install unknown apps** for whatever browser/file manager you used.
3. Open the APK to install.
4. Launch Candela. You'll be asked for notification permission (used for the lock-screen tile during playback). The voice picker appears on first launch — pick a Piper voice for a quick first chapter (~14–30 MB) or Kokoro for the multi-speaker model (~330 MB).

System requirements:

- Android 8.0 (API 26) or higher
- ~210 MB free storage for the APK; voice models add ~14 MB (Piper / KittenTTS) to ~330 MB (Kokoro)
- An internet connection for browsing, chapter download, and the first-time voice download (chapters and voices cache locally)

## Optional: sign in to Royal Road

Anonymous browsing works for all public chapters. **Sign in** unlocks:

- Premium chapters (Patreon-tier early access)
- Your Follows tab — your bookmarked fictions sync down

Candela uses an in-app WebView for the login flow. Your password never touches our code; only the session cookies are captured (and stored encrypted on-device).

## Build from source

Requires JDK 17, Android SDK 36, and a system gradle ≥ 8.10 for the wrapper bootstrap (the wrapper downloads Gradle 9.4.1).

```sh
git clone https://github.com/techempower-org/candela.git
cd candela

# One-time bootstrap
gradle wrapper --gradle-version 9.4.1 --distribution-type bin
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build
./gradlew :app:assembleRelease        # phone APK (shipped variant)
./gradlew :wear:assembleDebug         # wear APK (wear stays on debug; no release variant yet)
./gradlew :app:installRelease         # install on connected device
# Local iteration with debugger attach:
./gradlew :app:installDebug           # non-minified, isDebuggable=true
```

The CI workflow (`.github/workflows/android.yml`) shows the canonical build steps.

## Architecture

```
┌─────────────────────────────────────────────┐
│  :app                                       │
│  Hilt root · NavHost · Settings adapters    │
└──────┬──────────────┬───────────────────────┘
       │              │
       │              ▼
       │       ┌──────────────────────┐
       │       │  :feature            │
       │       │  Library / Follows / │
       │       │  Browse / Reader /   │
       │       │  Detail / Settings / │
       │       │  AI Chat             │
       │       └──┬──────┬─────┬──────┘
       │          │      │     │
       ▼          ▼      ▼     ▼
┌────────────┐ ┌─────────────┐ ┌───────────────┐ ┌───────────────┐
│ :core-data │ │ :core-      │ │  :core-llm    │ │  :core-ui     │
│  Room +    │ │  playback   │ │  Provider     │ │  Library      │
│  repos +   │ │  EnginePlyr │ │  matrix       │ │  Nocturne     │
│  @Source-  │ │  + PcmCache │ │  (Claude /    │ │  theme +      │
│  Plugin    │ │  + Voice    │ │   Teams /     │ │  components   │
│  registry  │ │  Manager +  │ │   OpenAI /    │ │               │
│  + plugin  │ │  Sentence   │ │   Vertex /    │ │               │
│  manager   │ │  Tracker    │ │   Bedrock /   │ │               │
│  state     │ │  (in-proc + │ │   Foundry /   │ │               │
│            │ │   Azure)    │ │   Ollama)     │ │               │
└─────┬──────┘ └──────┬──────┘ └───────┬───────┘ └───────────────┘
      │               │                │
      │               │ JitPack:       │ ChatStreamEvent +
      │               │ VoxSherpa-TTS  │ ToolCatalog (function
      │               │ :engine-lib    │ calling, v0.5.33)
      │               │ (Piper /       │ + ImageContentBlock
      │               │  Kokoro /      │ (multi-modal, v0.5.36)
      │               │  KittenTTS /   │
      │               │  Supertonic)   │
      ▼               │                ▼
┌─────────────────────────────┐  ┌──────────────────────┐
│ Fiction sources (27)        │  │ :core-sync           │
│ ─────────────────────────── │  │ InstantDB sync —     │
│ :source-royalroad           │  │ library / follows /  │
│ :source-github              │  │ positions / book-    │
│ :source-rss                 │  │ marks / pronuncia-   │
│ :source-epub                │  │ tion / secrets       │
│ :source-outline             │  │ (#360 v0.5.12)       │
│ :source-mempalace           │  └──────────────────────┘
│ :source-gutenberg           │
│ :source-ao3                 │  ┌──────────────────────┐
│ :source-standard-ebooks     │  │ :core-plugin-ksp     │
│ :source-wikipedia           │  │ KSP processor —      │
│ :source-wikisource          │  │ @SourcePlugin →      │
│ :source-radio (KVMR/etc.)   │  │ Hilt @IntoSet        │
│ :source-notion              │  │ factory (v0.5.27)    │
│ :source-hackernews          │  └──────────────────────┘
│ :source-arxiv               │
│ :source-plos                │  ┌──────────────────────┐
│ :source-discord             │  │ :baselineprofile     │
│ :source-telegram   (v0.5.51)│  │ Hot-path producer —  │
│ :source-palace     (v0.5.51)│  │ baseline-prof.txt    │
│ :source-slack      (v0.5.51)│  │ → cold launch 0.8 s  │
│ :source-matrix     (v0.5.51)│  │ on Tab A7 Lite       │
│                             │  │ (#409 v0.5.46)       │
│ Catch-all + writer          │  └──────────────────────┘
│ ─────────────────────────── │
│ :source-readability         │
│ :source-epub-writer         │
│ :source-audiobook-writer    │
│                             │
│ Local files (v1.1.0):       │
│ :source-pdf                 │
│ :source-ocr                 │
│ :source-librivox            │
│                             │
│ TTS backends                │
│ ─────────────────────────── │
│ :source-azure (cloud BYOK)  │
└─────────────────────────────┘
                         ▼
                   (audio out)
```

40 Gradle modules now (was 13 at v0.4.x; 29 at v0.5.38). Fiction sources implement `FictionSource` from `:core-data` and self-register via the `@SourcePlugin` annotation; the `:core-plugin-ksp` KSP processor emits a Hilt `@IntoSet` factory per annotated class, so `SourcePluginRegistry` discovers them at startup. Adding a new backend is ~4 touchpoints today (was ~17 pre-Phase-2). The playback layer is independent of the UI; the local engine library is a single transitive dep on `:core-playback`. AI chat lives in its own `:core-llm` module — the seven-provider matrix shares one `ChatStreamEvent` flow type that carries text deltas + tool-call events + tool-result events end-to-end. Cross-device sync lives in `:core-sync` against InstantDB. A `:baselineprofile` producer module (UI Automator hot-path walk) emits `baseline-prof.txt` for the AndroidX Baseline Profile plugin — cold launch dropped 6.7 s → 0.8 s on Tab A7 Lite (v0.5.46).

Design specs (each shipped or in flight) read as a thread:

- [Storyvox baseline](docs/superpowers/specs/2026-05-05-storyvox-design.md) — original architecture
- [GitHub source](docs/superpowers/specs/2026-05-06-github-source-design.md) — second fiction source
- [PCM cache](docs/superpowers/specs/2026-05-07-pcm-cache-design.md) — render-to-disk for slow-voice gapless playback
- [Engine knobs](docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md) — engine settings catalog
- [Settings redesign](docs/superpowers/specs/2026-05-08-settings-redesign-design.md) — grouped-card structure
- [Azure HD voices](docs/superpowers/specs/2026-05-08-azure-hd-voices-design.md) — BYOK cloud TTS
- [GitHub OAuth](docs/superpowers/specs/2026-05-08-github-oauth-design.md) — your private repos as fictions

Per-dreamer detail specs live in `scratch/dreamers/`.

## Stack

| | |
|---|---|
| Language | Kotlin 2.3 |
| UI | Jetpack Compose, Material 3 |
| DI | Hilt (KSP) |
| Storage | Room, DataStore Preferences, EncryptedSharedPreferences |
| Networking | OkHttp + Jsoup (RR is HTML, not JSON) + commonmark (GitHub markdown) |
| Playback | Media3 SimpleBasePlayer + custom AudioTrack pipeline + Tier 3 multi-engine producer |
| TTS (local) | VoxSherpa-TTS engine-lib (Piper + Kokoro + KittenTTS + Supertonic on sherpa-onnx), in-process · plus the device's built-in System TTS |
| TTS (cloud, optional) | Azure Cognitive Services HD voices (BYOK) with offline fallback |
| Async | Coroutines + Flow |
| Wear OS | Compose for Wear, `play-services-wearable` |
| CI | GitHub Actions |

## Roadmap

The v0.4 line shipped 80+ point releases — the engine, six fiction sources, AI chat, OAuth, the Settings redesign, Azure HD as a remote TTS option, and the Tier 3 perf lane are all in. The next wave is the v0.5 line: better recall, more shaping of the read-aloud, and the long-promised knowledge graph.

**Shipped in v0.4 (since v0.4.55):**
- **Azure HD voices (BYOK).** Optional cloud TTS via Azure Cognitive Services. Settings UI ([#182](https://github.com/techempower-org/storyvox/issues/182)), engine wiring ([#183](https://github.com/techempower-org/storyvox/issues/183)), error handling and retries ([#184](https://github.com/techempower-org/storyvox/issues/184)), offline fallback ([#185](https://github.com/techempower-org/storyvox/issues/185)), full voice roster + cache eviction priority ([#186](https://github.com/techempower-org/storyvox/issues/186)). Bring your own key — never billed by Candela.
- **Tier 3 multi-engine parallel synthesis.** Twin sliders for Engines × Threads/engine in Settings → Performance, producer pinned to a dedicated `URGENT_AUDIO` thread, engine multi-core synced with upstream main.
- **EPUB import** ([#235](https://github.com/techempower-org/storyvox/issues/235)). Folder picker via Storage Access Framework + an OPF parser; any folder of EPUB files becomes a Browse tab.
- **RSS / Atom feeds** ([#236](https://github.com/techempower-org/storyvox/issues/236)). Subscribe to any feed; suggested feeds curated in [candela-feeds](https://github.com/techempower-org/candela-feeds) ([#246](https://github.com/techempower-org/storyvox/issues/246)).
- **Outline self-hosted-wiki backend** ([#245](https://github.com/techempower-org/storyvox/issues/245)). Treat your Outline collections as fictions, articles as chapters.
- **Smart-resume CTA.** Library Resume button respects last paused/playing intent — no more surprise auto-play.
- **AI Sessions surface** ([#218](https://github.com/techempower-org/storyvox/issues/218)) — Settings → AI → Sessions to review past chats.
- **AI read-aloud per assistant turn** ([#214](https://github.com/techempower-org/storyvox/issues/214)) — speak any chat reply through the same TTS pipeline.
- **Per-voice speed and pitch defaults** ([#195](https://github.com/techempower-org/storyvox/issues/195)).
- **Punctuation cadence drives Kokoro `silence_scale`** ([#196](https://github.com/techempower-org/storyvox/issues/196)) — one slider, two engines, consistent feel.
- **Stable debug-keystore signing** — clean upgrades over older debug builds without uninstall.

**v0.5 candidates:**
- **Knowledge graph for fiction.** Per-book Notebook (characters, places, who-said-what) seeding into MemPalace ([#147](https://github.com/techempower-org/storyvox/issues/147)).
- **Engine knob exposure.** Loudness normalization, breath pause, pitch envelope as user-tunable settings ([research draft](docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md)).
- **PCM cache PRs C–H.** Auto-population, settings UI for cache size, graceful fallback ([#86](https://github.com/techempower-org/storyvox/issues/86)).

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the long-form roadmap and backlog.

## How it was built

Candela was built starting May 5, 2026 by JP Hein orchestrating teams of dream-named [Claude Opus](https://www.anthropic.com/claude) agents working in parallel via [Claude Code](https://www.anthropic.com/claude-code). The original five-act structure shipped the v0.3 line:

1. **storyvox-dreamers** — Morpheus, Selene, Oneiros, Hypnos, Aurora drafted the architecture, data layer, Royal Road integration, playback engine, and design system.
2. **storyvox-tonight** — Phantasos, Morrigan, Caelus wired live audio playback, library round-trip, and loading skeletons.
3. **storyvox-finish** — Iris, Aether, Pheme, Janus added the sentence-highlight reader, player polish (speed/sleep/voice), MediaStyle notification, and CI/CD.
4. **storyvox-features-2** — Athena, Themis, Hestia shipped sign-in, full RR filters, and responsive grids.
5. **Davis** orchestrated all four teams, integrated their work, verified on a Galaxy Tab A7 Lite, committed and pushed.

The v0.4 line landed the in-process neural TTS engine, voice library, GitHub fiction source, infinite-scroll browse, motion polish, and the PCM cache filesystem layer. Aurelia owned the perf lane and benched Piper-high "cori" at 0.285× realtime on the target device — the number that justifies the cache work. Bryn shipped Performance Mode A/B toggles. The voice picker grew tiers, stars, and a Starred surface.

The May 8 round was the largest single-day landing yet — 30+ agents in parallel under JP's orchestration. Indigo specced the Settings redesign; Saga implemented it. Solara specced Azure HD; Ember specced GitHub OAuth; Thalia catalogued every engine knob worth exposing. Iris refreshed the README and clarified the GPL-3.0 license posture (downstream obligation, not branding). Calliope chased an auth-cookie race. Briar polished the README you're reading.

Through the v0.4.56 → v0.4.83 stretch the dream-team kept landing: Solara's Azure work shipped end-to-end (BYOK Settings → engine wiring → retries → offline fallback → roster + eviction priority); Reeve and Lyra opened the source surface to RSS, EPUB, and Outline; Aurelia cut Tier 3 multi-engine parallel synthesis with twin Engines/Threads sliders; Hazel landed the smart-resume CTA so the Library button stops auto-playing at you. The dream-team retro reads as a thread of small, named contributions — each commit message preserves who did what, and `git log` reads as the credits.

## License

Candela is licensed under the [GNU General Public License v3.0](LICENSE).

We statically link [VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) (GPL-3.0) into the APK as our TTS engine. The combined work is therefore GPL-3.0 — this license is not a posture choice, it's a downstream obligation. Relicensing more permissively would require replacing the engine, not just changing this file.

You're free to use, modify, and redistribute under the terms of the GPL-3.0.
