---
layout: default
title: Candela — TechEmpower's accessible resource app, with audiobook everything
description: Listen to ebooks, follow serial fiction, wake up to a narrated briefing, rehearse with a teleprompter, create audiobooks, scan pages, and hear any link read aloud — thirty-four sources, four on-device neural voice families, free and open, no account, no tracking.
image: /screenshots/03-reader.png
---

<section class="hero">
  <div class="hero-text">
    <h1>Candela</h1>
    <p class="tagline"><strong><a href="https://techempower.org">TechEmpower</a>'s accessible resource app.</strong> Browse free tech guides, connect with peer-support Discord, dial 211 for local help — and listen to any of it through a neural-voice audiobook engine that reads everything aloud.</p>
    <p>
      Under the hood: thirty-four fiction backends side by side — <a href="https://royalroad.com">Royal Road</a>,
      <a href="https://github.com">GitHub</a>, RSS feeds, EPUB files on your device,
      <a href="https://www.getoutline.com">Outline</a> wikis, your self-hosted
      <a href="https://github.com/techempower-org/mempalace">Memory Palace</a>,
      <a href="https://www.gutenberg.org/">Project Gutenberg</a>, AO3, Standard Ebooks,
      <a href="https://librivox.org">LibriVox</a>, Wikipedia,
      Wikisource, Radio (30k+ stations), <a href="https://notion.so">Notion</a> (defaults to
      TechEmpower's resource library — Guides, Resources, About, Donate), Hacker News,
      <a href="https://news.google.com">Google News</a>,
      arXiv, PLOS, Discord, <a href="https://telegram.org">Telegram</a>,
      <a href="https://thepalaceproject.org">Palace Project</a>,
      <a href="https://www.bookshare.org">Bookshare</a>, <a href="https://slack.com">Slack</a>,
      <a href="https://matrix.org">Matrix</a> — all read aloud by an <strong>in-process neural TTS engine</strong>
      that runs entirely on-device. A hybrid reader/audiobook view highlights the spoken sentence
      in brass as you listen.
    </p>
    <p class="cta-row">
      <a class="cta-primary" id="cta-download"
         href="https://github.com/techempower-org/candela/releases/latest"
         data-base-href="https://github.com/techempower-org/candela/releases">
        <span class="cta-label">Download latest APK</span>
        <span class="cta-version" aria-hidden="true"></span>
      </a>
      <a class="cta-secondary" href="https://github.com/techempower-org/candela">Source on GitHub</a>
      <a class="cta-tertiary" href="install/">Install guide →</a>
    </p>
    <p class="cta-fineprint muted">
      Sideload, Android 8.0+, ~210 MB APK. No Play Store. No tracking. No in-app purchases.
    </p>
  </div>
  <div class="hero-art">
    <dark-image
      src-dark="screenshots/03-reader.png"
      src-light="screenshots/03-reader-light.png"
      alt="Candela reader playing The Archmage Coefficient with the spoken sentence highlighted in brass.">
      <img src="screenshots/03-reader.png" alt="Candela reader playing The Archmage Coefficient with the spoken sentence highlighted in brass." />
    </dark-image>
  </div>
</section>

<style>
  .uses { margin-top: 2.2em; }
  .uses-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
    gap: 0.9em;
    margin: 1.3em 0 0.9em;
  }
  .use-card {
    background: var(--warm-bg-card);
    border: 1px solid var(--warm-rule);
    border-left: 3px solid var(--brass-600);
    border-radius: var(--radius-lg);
    padding: 0.95em 1.1em;
    transition: border-color 0.15s ease;
  }
  .use-card:hover { border-color: var(--brass-600); border-left-color: var(--brass-300); }
  .use-card h3 { margin: 0 0 0.3em; font-size: 1.02em; color: var(--brass-300); }
  .use-card p { margin: 0; font-size: 0.92em; color: var(--warm-fg-muted); line-height: 1.45; }
  .uses-kicker { text-align: center; margin-top: 1.1em; }
</style>

<section class="uses">
  <h2>What will you do with it?</h2>
  <p class="muted">
    One engine, many lives. Every card below is shipped and working today — no roadmap items.
  </p>
  <div class="uses-grid">
    <div class="use-card">
      <h3>Listen to ebooks</h3>
      <p>70,000+ public-domain classics and your own EPUBs, narrated by neural voices that live on your phone.</p>
    </div>
    <div class="use-card">
      <h3>Follow serial fiction</h3>
      <p>New Royal Road and AO3 chapters land in your inbox and read themselves aloud.</p>
    </div>
    <div class="use-card">
      <h3>Wake up to a briefing</h3>
      <p>One tap stitches Hacker News, arXiv, your feeds, and GitHub into a single narrated morning episode.</p>
    </div>
    <div class="use-card">
      <h3>Hear your own things</h3>
      <p>Notion pages, Outline wikis, your Memory Palace, chat channels, PDFs — if you can read it, it can speak.</p>
    </div>
    <div class="use-card">
      <h3>Create an audiobook</h3>
      <p>Render any book to a chaptered M4B that's yours to keep — or export a clean EPUB.</p>
    </div>
    <div class="use-card">
      <h3>Rehearse a show</h3>
      <p>A teleprompter that scrolls itself — or follows your voice through the mic — with takes recorded in place.</p>
    </div>
    <div class="use-card">
      <h3>Capture a voice note</h3>
      <p>Record a thought, memo, or meeting. It transcribes on-device; then, only if you tap Summarize, it becomes an AI note. Audio never leaves the phone.</p>
    </div>
    <div class="use-card">
      <h3>Scan a page &amp; listen</h3>
      <p>Point the camera at paper; on-device OCR turns it into narration. Nothing leaves the phone.</p>
    </div>
    <div class="use-card">
      <h3>Listen to any link</h3>
      <p>Share a URL from any app and the magic reader queues it as a chapter. No link is a dead-end.</p>
    </div>
    <div class="use-card">
      <h3>Study with your ears</h3>
      <p>Synced sentence highlighting, tap-to-define, in-book search, and AI chapter recaps with your own key.</p>
    </div>
    <div class="use-card">
      <h3>Drive and stroll with it</h3>
      <p>Android Auto on the dashboard, a Wear OS remote and teleprompter on your wrist.</p>
    </div>
    <div class="use-card">
      <h3>Take it off-grid</h3>
      <p>Download whole books over Wi-Fi and listen with zero signal — built for metered data plans.</p>
    </div>
    <div class="use-card">
      <h3>Fall asleep to it</h3>
      <p>A sleep timer with an end-of-chapter mode and shake-to-extend as the fade begins.</p>
    </div>
  </div>
  <p class="uses-kicker muted">
    All of it free, open source, and on-device — no account, no ads, no tracking.
    The machinery: <a href="#sources">thirty-four sources</a> and
    <a href="#voices">four voice families</a>, below.
  </p>
</section>

<section class="why">
  <h2>Why Candela</h2>
  <div class="why-grid">
    <div class="card">
      <h3>TechEmpower Home</h3>
      <p>
        A dedicated TechEmpower screen ties the resource app together: free tech guides, an
        About panel explaining the 501(c)(3) mission, a Browse-the-library shortcut, and the
        two help paths — <strong>peer-support Discord</strong> and <strong>call 211</strong> for
        local services. Designed for users who came for help, not for fiction.
      </p>
    </div>
    <div class="card">
      <h3>Peer support, on-tap</h3>
      <p>
        Tap once to open the TechEmpower
        <a href="https://discord.gg/j3SVttxw7k">peer-support Discord</a> — real volunteers,
        no chatbot. Tap to dial <strong>211</strong> for local United Way services. Both routes
        live alongside the library, never buried under a Settings menu.
      </p>
    </div>
    <div class="card">
      <h3>On-device neural TTS</h3>
      <p>
        Four voice families ship — <a href="https://github.com/rhasspy/piper">Piper</a> (compact),
        <a href="https://github.com/hexgrad/kokoro">Kokoro</a> (multi-speaker),
        <strong>KittenTTS</strong> (lightest tier, designed for slow devices), and
        <strong>Supertonic&nbsp;3</strong> (HD). Voices download
        once, then live on-device. No cloud, no API keys, no per-character billing.
      </p>
    </div>
    <div class="card">
      <h3>Reader view, in sync</h3>
      <p>
        Swipe between audiobook view (cover + scrubber + transport) and reader view (chapter text).
        The current sentence glides along in brass, matching the read-aloud rhythm — so you can
        listen, read, or both at once.
      </p>
    </div>
    <div class="card">
      <h3>AI chat per fiction</h3>
      <p>
        Per-book chat across seven LLM providers, with grounding (current sentence / chapter /
        whole book), cross-fiction memory, function calling ("queue chapter 5", "open Voice
        Library"), and multi-modal image input. Brass-edged tool cards show in-flight state.
      </p>
    </div>
    <div class="card">
      <h3>Smooth on slow hardware</h3>
      <p>
        Cold launch in <strong>0.8 s</strong> on a Galaxy Tab A7 Lite (down from 6.7 s) — R8
        minification, Baseline Profile, and <code>isDebuggable=false</code> in release builds.
        Tier 3 multi-engine parallel synthesis (1–8 VoxSherpa instances × N threads each, twin
        sliders in Settings → Performance) plus PCM cache buffering keep playback gapless.
      </p>
    </div>
    <div class="card">
      <h3>Beautiful Notion covers</h3>
      <p>
        TechEmpower's Notion-backed library renders with proper page covers and brass-edged
        synthetic tiles for pages with body images instead of explicit covers. The fallback uses
        a Library Nocturne palette so even cover-less pages look intentional.
      </p>
    </div>
    <div class="card">
      <h3>Optional cloud voices (BYOK)</h3>
      <p>
        Bring your own Azure key for studio-grade
        <a href="https://learn.microsoft.com/azure/ai-services/speech-service/text-to-speech">Azure HD voices</a>.
        Offline fallback to your local voice if the network drops or your key expires. Opt-in,
        never required, never billed by Candela.
      </p>
    </div>
    <div class="card">
      <h3>Accessibility-first</h3>
      <p>
        High-contrast brass-on-near-black theme passes WCAG AA; <code>prefers-reduced-motion</code>
        collapses fold-in animations; TalkBack pacing tuned to chapter-list patterns. A dozen
        a11y audit findings closed and the surface kept under review.
      </p>
    </div>
    <div class="card">
      <h3>Brass on warm dark</h3>
      <p>
        Library Nocturne theme — brass accents, EB Garamond chapter body, Inter UI. Light mode
        is parchment cream. Wear OS gets the same theme with a circular brass scrubber. Adaptive
        grid: phones (2 col), tablets (5), foldables (more).
      </p>
    </div>
  </div>
</section>

<section class="sources">
  <h2 id="sources">Thirty-four fiction backends, side by side</h2>
  <p class="muted">
    A plugin-seam architecture means each backend is ~4 touchpoints. Adding a new one auto-surfaces
    in <strong>Settings → Plugins</strong>. Each has its own on/off toggle.
  </p>
  <div class="sources-grid">
    <a class="source-card" href="https://royalroad.com">
      <span class="source-glyph" aria-hidden="true">RR</span>
      <h3>Royal Road</h3>
      <p>The full filter set — tags include/exclude, status, type, length, rating, content warnings, sort. Follows tab syncs your bookmarks.</p>
    </a>
    <a class="source-card" href="https://github.com/techempower-org/candela-registry">
      <span class="source-glyph" aria-hidden="true">GH</span>
      <h3>GitHub</h3>
      <p>Curated <strong>candela-registry</strong> plus live <code>/search/repositories</code>. OAuth Device Flow lifts the 60→5000 req/hr cap.</p>
    </a>
    <a class="source-card" href="https://github.com/techempower-org/candela-feeds">
      <span class="source-glyph" aria-hidden="true">RSS</span>
      <h3>RSS / Atom feeds</h3>
      <p>Any RSS or Atom feed, plus a managed suggested-feeds list from <strong>candela-feeds</strong>.</p>
    </a>
    <a class="source-card" href="https://www.getoutline.com">
      <span class="source-glyph" aria-hidden="true">OL</span>
      <h3>Outline</h3>
      <p>Self-hosted Outline wiki as a fiction backend. Paste your URL + API token; collections become fictions, documents become chapters.</p>
    </a>
    <a class="source-card" href="https://github.com/techempower-org/mempalace">
      <span class="source-glyph" aria-hidden="true">MP</span>
      <h3>Memory Palace</h3>
      <p>Your own self-hosted Memory Palace. Drawers become chapters; the palace becomes a personal canon.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">EPUB</span>
      <h3>Local EPUB</h3>
      <p>Open any folder via the system file picker. OPF parser splits EPUBs into chapters; works fully offline.</p>
    </a>
    <a class="source-card" href="https://www.gutenberg.org/">
      <span class="source-glyph" aria-hidden="true">PG</span>
      <h3>Project Gutenberg</h3>
      <p>70,000+ public-domain books. Search by author, title, or subject; download EPUB or read inline.</p>
    </a>
    <a class="source-card" href="https://archiveofourown.org/">
      <span class="source-glyph" aria-hidden="true">AO3</span>
      <h3>Archive of Our Own</h3>
      <p>Per-tag feeds and official EPUBs. Browse by fandom, ship, or trope tag.</p>
    </a>
    <a class="source-card" href="https://standardebooks.org/">
      <span class="source-glyph" aria-hidden="true">SE</span>
      <h3>Standard Ebooks</h3>
      <p>Hand-curated, typographically polished public-domain classics. The good edition.</p>
    </a>
    <a class="source-card" href="https://en.wikipedia.org/">
      <span class="source-glyph" aria-hidden="true">W</span>
      <h3>Wikipedia</h3>
      <p>Any article, heading-split into chapters. Long-form articles become quick audiobooks.</p>
    </a>
    <a class="source-card" href="https://en.wikisource.org/">
      <span class="source-glyph" aria-hidden="true">WS</span>
      <h3>Wikisource</h3>
      <p>Walks multi-part works as <code>/Subpage</code> chapters. Free, primary-source texts.</p>
    </a>
    <a class="source-card" href="https://www.radio-browser.info/">
      <span class="source-glyph" aria-hidden="true">FM</span>
      <h3>Radio</h3>
      <p>Five curated stations (KVMR, Cap Public, KQED, KCSB, SomaFM) plus <strong>Radio Browser</strong> search across 30,000+ stations.</p>
    </a>
    <a class="source-card" href="https://notion.so">
      <span class="source-glyph" aria-hidden="true">N</span>
      <h3>Notion</h3>
      <p>Any Notion page or database — defaults to the techempower.org resource library (Guides, Resources, About, Donate). Beautiful page covers + body-image fallback render brass-edged synthetic tiles for cover-less pages.</p>
    </a>
    <a class="source-card" href="https://news.ycombinator.com/">
      <span class="source-glyph" aria-hidden="true">HN</span>
      <h3>Hacker News</h3>
      <p>Top stories + Ask HN / Show HN threads with comments narrated in order.</p>
    </a>
    <a class="source-card" href="https://news.google.com/">
      <span class="source-glyph" aria-hidden="true">GN</span>
      <h3>Google News</h3>
      <p>Topic feeds narrated as chapters — headlines and articles read in order, with Cloudflare-aware fetching.</p>
    </a>
    <a class="source-card" href="https://arxiv.org/">
      <span class="source-glyph" aria-hidden="true">arX</span>
      <h3>arXiv</h3>
      <p>Abstracts in cs.AI and other categories — let the neural voice read the cutting edge while you commute.</p>
    </a>
    <a class="source-card" href="https://plos.org/">
      <span class="source-glyph" aria-hidden="true">PLOS</span>
      <h3>PLOS</h3>
      <p>Open-access, peer-reviewed science papers. Hear research instead of skimming it.</p>
    </a>
    <a class="source-card" href="https://discord.com/">
      <span class="source-glyph" aria-hidden="true">DC</span>
      <h3>Discord</h3>
      <p>Serialized fiction in Discord channels — channels are fictions, messages are chapters. Bot-token auth.</p>
    </a>
    <a class="source-card" href="https://telegram.org/">
      <span class="source-glyph" aria-hidden="true">TG</span>
      <h3>Telegram</h3>
      <p>Public Telegram channels — invite the bot, channels become fictions, messages become chapters. Simpler shape than Discord (no threads, no servers).</p>
    </a>
    <a class="source-card" href="https://thepalaceproject.org/">
      <span class="source-glyph" aria-hidden="true">PP</span>
      <h3>Palace Project</h3>
      <p>First library-borrowing backend — OPDS catalog walker for the Palace Project's free library titles. Non-DRM titles in this PR; LCP DRM deferred.</p>
    </a>
    <a class="source-card" href="https://www.bookshare.org/">
      <span class="source-glyph" aria-hidden="true">BS</span>
      <h3>Bookshare</h3>
      <p>The world's largest collection of accessible ebooks, for readers with print disabilities — discovery is wired; full access awaits the partner key.</p>
    </a>
    <a class="source-card" href="https://slack.com/">
      <span class="source-glyph" aria-hidden="true">SL</span>
      <h3>Slack</h3>
      <p>Slack channels as fictions via the Web API. Bot-token (xoxb-…) auth. Default OFF — workspaces are private and onboarding is high-friction.</p>
    </a>
    <a class="source-card" href="https://matrix.org/">
      <span class="source-glyph" aria-hidden="true">MX</span>
      <h3>Matrix</h3>
      <p>Federated open-standard chat (matrix.org, kde.org, FOSDEM, self-hosted Synapse / Dendrite / Conduit) — rooms are fictions, messages are chapters with same-sender coalescing.</p>
    </a>
    <a class="source-card" href="https://librivox.org">
      <span class="source-glyph" aria-hidden="true">LV</span>
      <h3>LibriVox</h3>
      <p>Public-domain audiobooks read by volunteers — streamed as real human narration (not TTS). Thousands of classic titles.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">PDF</span>
      <h3>Local PDF</h3>
      <p>Open a PDF from your device and have its text read aloud, page by page.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">OCR</span>
      <h3>Scanned text (OCR)</h3>
      <p>Point your camera at a page — on-device ML Kit turns the photo into narrated text. Nothing leaves the phone.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">RD</span>
      <h3>reddit</h3>
      <p>Subreddits as books, posts as chapters — full bodies via reddit's sanctioned API with your own free key, not truncated RSS stubs.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">GD</span>
      <h3>Google Drive</h3>
      <p>Authorize folders as a library — only what you pick, never your whole Drive. Google Docs read natively.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">CAL</span>
      <h3>Your calendar</h3>
      <p>Today, tomorrow, and the week ahead narrated from the phone's own calendar. On-device only — events never leave the phone.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">EPIC</span>
      <h3>Epic free games</h3>
      <p>The weekly giveaway rotation as narrated chapters — what's free now and what's coming.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">PG</span>
      <h3>Prime Gaming</h3>
      <p>Claimable titles via the community LootScraper feed — honestly labeled: claiming needs a Prime subscription.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">URL</span>
      <h3>Any web article</h3>
      <p>Paste any link — Readability extraction turns an arbitrary web page into a single-chapter fiction. No URL is a dead-end.</p>
    </a>
    <a class="source-card">
      <span class="source-glyph" aria-hidden="true">DOC</span>
      <h3>Candela Handbook</h3>
      <p>The app's own user guide, shipped as a bundled narrated book — read the manual aloud, fully offline. No network, no permissions.</p>
    </a>
  </div>
</section>

<section class="voices">
  <h2 id="voices">Four voice families, all on-device</h2>
  <p class="muted">
    Voices download on demand from the <code>voices-v2</code> release; nothing is bundled in the APK.
    The voice picker shows what's installed and what's available. <a href="voices/">Full voice catalog →</a>
  </p>
  <div class="voice-grid">
    <div class="voice-card">
      <div class="voice-tier">Compact</div>
      <h3>Piper</h3>
      <p class="voice-size">~14–30 MB per voice</p>
      <p>
        Single-speaker neural voices in dozens of languages. Quality / x-low / low / medium /
        high tiers per voice. Punches well above its weight on phones from 2018.
      </p>
      <p class="voice-meta muted">
        <a href="https://github.com/rhasspy/piper">rhasspy/piper</a>
      </p>
    </div>
    <div class="voice-card voice-card-flagship">
      <div class="voice-tier">Multi-speaker</div>
      <h3>Kokoro</h3>
      <p class="voice-size">~330 MB (shared across voices)</p>
      <p>
        One model, many speakers — male, female, and accent variants share weights. The sweet
        spot for modern Android tablets. Brass-warm narration that doesn't sound robotic.
      </p>
      <p class="voice-meta muted">
        <a href="https://github.com/hexgrad/kokoro">hexgrad/kokoro</a>
      </p>
    </div>
    <div class="voice-card">
      <div class="voice-tier">Lightest</div>
      <h3>KittenTTS</h3>
      <p class="voice-size">~24 MB (shared, 8 en_US speakers)</p>
      <p>
        The new lightest tier — designed for slow devices where Piper-high struggles. Eight en_US
        speakers share a single 24 MB model. The "first chapter in 10 seconds" voice family.
      </p>
      <p class="voice-meta muted">In-tree (Candela)</p>
    </div>
    <div class="voice-card">
      <div class="voice-tier">HD</div>
      <h3>Supertonic&nbsp;3</h3>
      <p class="voice-size">Shared model, 10 HD en_US speakers</p>
      <p>
        The newest family — ten expressive, high-definition English speakers from one shared
        model. The pick when narration quality matters more than download size.
      </p>
      <p class="voice-meta muted">In-tree (Candela)</p>
    </div>
  </div>
  <p class="voices-cloud">
    <strong>Zero-download fallback:</strong> your device's own <strong>System TTS</strong> voices work
    out of the box — the first chapter can speak before any model downloads.
    <strong>Optional cloud:</strong> Bring your own
    <a href="https://learn.microsoft.com/azure/ai-services/speech-service/text-to-speech">Azure HD</a>
    key for studio-grade narration on slow devices. Offline fallback to your local voice if your
    key fails or the network drops. Opt-in, never required.
  </p>
</section>

<section class="screens">
  <h2>What it looks like</h2>
  <p class="muted">Galaxy Tab A7 Lite, 800×1340 px. Tap the theme toggle (top right) to flip light/dark. <a href="screenshots/">Full gallery →</a></p>
  <div class="screens-grid">
    <figure>
      <dark-image src-dark="screenshots/01-browse.png" src-light="screenshots/01-browse-light.png" alt="Browse tab">
        <img src="screenshots/01-browse.png" alt="Browse tab" loading="lazy" />
      </dark-image>
      <figcaption>Browse — infinite-scroll across every source.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/02-detail.png" src-light="screenshots/02-detail-light.png" alt="Fiction detail">
        <img src="screenshots/02-detail.png" alt="Fiction detail" loading="lazy" />
      </dark-image>
      <figcaption>Fiction detail — synopsis, tags, chapter list with read state.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/04-library.png" src-light="screenshots/04-library-light.png" alt="Library tab">
        <img src="screenshots/04-library.png" alt="Library tab" loading="lazy" />
      </dark-image>
      <figcaption>Library — TechEmpower hero card on top, currently-listening with progress + smart resume below, four-tab dock <code>{Playing · Library · Voices · Settings}</code> anchored at the bottom.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/08-techempower-home.png" src-light="screenshots/08-techempower-home-light.png" alt="TechEmpower Home">
        <img src="screenshots/08-techempower-home.png" alt="TechEmpower Home" loading="lazy" />
      </dark-image>
      <figcaption>TechEmpower Home — peer-support Discord, dial 211, Browse the resource library, About TechEmpower.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/09-accessibility-settings.png" src-light="screenshots/09-accessibility-settings-light.png" alt="Accessibility settings">
        <img src="screenshots/09-accessibility-settings.png" alt="Accessibility settings" loading="lazy" />
      </dark-image>
      <figcaption>Accessibility — high contrast, reduced motion, larger touch targets, screen-reader pauses, font scale override.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/06-filter-dark.png" src-light="screenshots/06-filter.png" alt="Royal Road filter sheet">
        <img src="screenshots/06-filter-dark.png" alt="Royal Road filter sheet" loading="lazy" />
      </dark-image>
      <figcaption>Royal Road filters — sort, tags include/exclude, content warnings.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/06b-filter-github-dark.png" src-light="screenshots/06b-filter-github.png" alt="GitHub filter sheet">
        <img src="screenshots/06b-filter-github-dark.png" alt="GitHub filter sheet" loading="lazy" />
      </dark-image>
      <figcaption>GitHub filters — stars, language, topics, last-pushed.</figcaption>
    </figure>
    <figure>
      <dark-image src-dark="screenshots/05-settings.png" src-light="screenshots/05-settings-light.png" alt="Settings hub">
        <img src="screenshots/05-settings.png" alt="Settings hub" loading="lazy" />
      </dark-image>
      <figcaption>Settings — brass-edged section hub (thirteen cards).</figcaption>
    </figure>
  </div>
</section>

<section class="open">
  <h2>Open. Free. Yours.</h2>
  <div class="open-grid">
    <div class="open-card">
      <h3>GPL-3.0</h3>
      <p>
        Inherited from the TTS engine — also a posture. Read the source, modify it, ship your
        fork. No closed components. <a href="https://github.com/techempower-org/candela/blob/main/LICENSE">License →</a>
      </p>
    </div>
    <div class="open-card">
      <h3>No telemetry</h3>
      <p>
        Zero analytics. Zero crash reporting. Zero "anonymous usage". The app talks to the
        backends you opt into, the voice repo for downloads, and nothing else.
      </p>
    </div>
    <div class="open-card">
      <h3>No in-app purchases</h3>
      <p>
        No subscriptions, no premium tier, no upsell. Azure HD is BYOK — you pay Microsoft
        directly if you want it. Candela doesn't take a cut.
      </p>
    </div>
    <div class="open-card">
      <h3>Sideload from GitHub</h3>
      <p>
        Not on the Play Store yet. <a href="https://github.com/techempower-org/candela/releases">Grab the APK from Releases</a>,
        enable "Install unknown apps" once, open the file. Three taps and you're in.
      </p>
    </div>
  </div>
</section>

<section class="recent">
  <h2>What just shipped</h2>
  <p>
    The current release and its notes always live on the
    <a href="https://github.com/techempower-org/candela/releases/latest">latest release</a> and in the
    <a href="https://github.com/techempower-org/candela/blob/main/CHANGELOG.md">changelog</a> — both
    always resolve to the newest version, so this page never goes stale.
  </p>
  <p>
    <strong>Where Candela is now:</strong> <strong>thirty-four fiction backends</strong> behind a
    plugin-seam architecture (a new backend is ~4 touchpoints — a <code>@SourcePlugin</code> annotation
    plus KSP-generated Hilt registration); <strong>four in-process neural voice families</strong>
    (Piper, Kokoro, KittenTTS, Supertonic 3) plus optional Azure HD cloud voices; a full <strong>PCM cache</strong>
    pipeline for glitch-free playback; a <strong>hybrid reader/audiobook view</strong> that highlights
    the spoken sentence in brass; <strong>Wear OS</strong> support; <strong>cross-device InstantDB
    sync</strong>; an accessibility-first design (high-contrast brass-on-near-black, reduced-motion,
    TalkBack pacing); and <strong>AI chat per fiction</strong> — cross-fiction memory, function calling,
    and multi-modal image input; and <strong>on-device Voice Notes</strong> — record a thought, transcribe it on-device, and (only if you tap Summarize) turn it into a consent-gated AI note. A <strong>TechEmpower-first</strong> home leads with Guides, Resources,
    peer-support Discord, and dial 211.
  </p>
  <p class="muted">
    See the <a href="https://github.com/techempower-org/candela/wiki">wiki</a> for build, voice
    catalog, and troubleshooting reference, or <a href="architecture/">how the modules fit
    together</a>.
  </p>
</section>

<footer class="site-footer">
  <p>
    Candela is licensed under the
    <a href="https://github.com/techempower-org/candela/blob/main/LICENSE">GNU General Public License v3.0</a>.
    Built by <a href="https://github.com/jphein">JP Hein</a>
    with teams of <a href="https://www.anthropic.com/claude-code">Claude Code</a> agents.
  </p>
  <p class="muted">
    <a href="/privacy/">Privacy policy</a> ·
    <a href="https://github.com/techempower-org/candela">Source</a> ·
    <a href="https://github.com/techempower-org/candela/issues">Report an issue</a>
  </p>
</footer>

<script>
  // Resolve the "Download latest APK" button to the actual signed APK asset from
  // the latest release on GitHub. Falls back to the Releases page on any error.
  (() => {
    const btn = document.getElementById('cta-download');
    if (!btn) return;
    const versionEl = btn.querySelector('.cta-version');
    fetch('https://api.github.com/repos/techempower-org/candela/releases/latest', {
      headers: { 'Accept': 'application/vnd.github+json' }
    })
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then(rel => {
        if (!rel || !rel.tag_name) return;
        // Prefer the signed APK asset; otherwise just deep-link to the release page.
        const apk = (rel.assets || []).find(a => /\.apk$/i.test(a.name));
        if (apk && apk.browser_download_url) {
          btn.href = apk.browser_download_url;
        } else if (rel.html_url) {
          btn.href = rel.html_url;
        }
        if (versionEl) versionEl.textContent = ' · ' + rel.tag_name;
      })
      .catch(() => { /* keep static fallback href */ });
  })();
</script>
