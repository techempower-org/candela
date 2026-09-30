# Sources / Browse

**(a) What exists.** There are **37 registered sources** from 34 `FictionSource` modules (`source-*`, `@SourcePlugin` → KSP → Hilt). The pieces:
- **Browse tab**: pinned source chips (TechEMPOWER, AO3, arXiv, …), **Browse all sources**, then a Popular/Search/Filter feed for the selected source.
- **Filters**: rendered generically by `DynamicFilterSheet` from each source's `filterDimensions()`.
- **Source Library** (`sources`, "37 sources to explore"): per-source **Enable** toggles and search.

**(b) How a user reaches it.** The **Browse** tab, then **Browse all sources** for the full library. Sources that need a sign-in (Royal Road, AO3, GitHub) also appear under Settings → Account, and API-key sources under Settings → Content Sources.

**(c) How to drive it.**
```bash
$V nav browse && $V text
$V tap "Browse all sources" && $V text       # Source Library
$V tap "Enable arXiv"                        # toggle a source (content-desc)
$V nav browse && $V tap Search && $V type accessibility && $V key enter   # not driven yet
```

**(d) What lies.**
- **Popular results are cover images with only a `content-desc`** ("Cover for Guides"). `text` shows them in brackets.
- **Network sources are live.** An empty or slow Popular list can mean the upstream site is slow or Cloudflare-challenged, not a Candela bug. The contract kit checks CF detection. Compare with a local source (TechEMPOWER) before concluding anything.
- **Enable toggles change persistent state** on the device. Toggle back afterwards on shared devices such as the tablet.
- A fiction id without its `sourceId:` prefix silently routes to Royal Road (#1564). If a browse result opens the wrong book, check the id first.
