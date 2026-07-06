# Keeping the offline screener in sync with techempower.org/qualify

**Status:** investigation + drift-detection shipped; content sync is a documented
manual step gated on the production corpus (#1586). Tracking: **#1605**.

Candela bundles an **offline benefits screener** (#1517): a "Do I qualify?" flow
that runs with zero connectivity, reading a bundled JSON asset instead of the
network. The same rules power the live web screener at **techempower.org/qualify**,
which the team edits continuously. This doc records where each corpus lives, how
they differ, and the mechanism that keeps them from silently diverging.

> **Invariant (epic #1520 #3 — "verified or silent").** Benefits *content* is
> TechEmpower-supplied from an adversarial fact-check pipeline, carries a
> verified-date, and is never authored or paraphrased in this repo. Any sync
> mechanism must **preserve provenance/verified-date stamping and never invent
> eligibility facts.** That constraint is why we *detect* drift here but do not
> *auto-copy* rules.

---

## 1. The app corpus (what ships on-device)

| | |
|---|---|
| **Asset** | `feature/src/main/assets/techempower/screener_corpus.json` |
| **Schema** | `feature/src/main/kotlin/in/jphe/storyvox/feature/techempower/screener/ScreenerCorpus.kt` |
| **Parser** | `ScreenerCorpusParser.kt` (pure, kotlinx.serialization, `ignoreUnknownKeys`) |
| **Loader** | `ScreenerViewModel.load()` — reads the asset, no network, no analytics |
| **Eligibility** | `ScreenerEligibility.kt` — pure, declarative criteria → LIKELY/MAYBE/UNLIKELY |
| **Program ids** | `ScreenerProgramIds.kt` — cross-lane id constants (wallet #1514, autofill #1519) |

**Schema (v1) shape:**

```
metadata { schemaVersion, provenance, verifiedDate, source, note }
questions[] { id, type(boolean|single_select), prompt{en,es}, options[]{id,label{en,es}} }
programs[]  { id, org, category, name{en,es}, summary{en,es}, phone, applyUrl,
              verifiedDate, sourceNote{en,es}, criteria[]{questionId, op, value} }
```

- `metadata.provenance` gates the UI's "sample data" banner. `"seed-sample"`
  (or anything ≠ `"techempower-verified"`) → the app loudly says the data is
  un-verified. Flip to `"techempower-verified"` and the banner disappears.
- **What ships today: a SEED SAMPLE.** `provenance: "seed-sample"`, **4 programs**
  (LIHEAP/Project GO, NID water discount, FREED battery backup, 211), 5 generic
  boolean/select questions. Its own `metadata.note` says: *"NOT the production
  corpus … The production corpus (33 verified programs, EN/ES) replaces this file
  wholesale."* The production corpus has **not landed** (tracked by **#1586**).

> ⚠️ **Doc accuracy bug:** `CHANGELOG.md` and #1517 describe the screener as
> "the client-side /qualify (33 programs) bundled on-device." The shipped asset
> has **4 seed programs**. Correct the copy when the production corpus lands (or
> sooner) — do not claim 33 while shipping 4.

---

## 2. The web source of truth (techempower.org/qualify)

Investigated 2026-07-05 (see #1605). Findings:

- **It's a Next.js static export.** `__NEXT_DATA__` is empty (162 B) — the rules
  are **not** in the SSG data payload. They are **baked into a minified,
  content-hash-named client chunk**: `/_next/static/chunks/pages/qualify-<hash>.js`
  (~320 KB). The hash changes on every deploy (observed changing *twice within
  one session* — the corpus is actively redeployed).
- **No published canonical JSON / API.** Probed `/qualify/corpus.json`,
  `/qualify.json`, `/api/qualify`, `/api/screener`, `list.techempower.org/…` etc.
  — all 404 → SPA fallback. The minified bundle is the only machine artifact.
- **The web schema is richer and different** from the app's:
  - `provenance` is an **array of claim objects**:
    `[{ claim, source: "https://…", verifiedAt: "YYYY-MM-DD", via: "<pipeline tag>" }]`
    — e.g. `via: "wave-1 research + oracle (verbatim)"`, `"ep3 wave: cf-ssi-seniors"`,
    `"ep4 wave: psps"`. (The app's `provenance` is a single string.)
  - FPL-based **income tables** per household size (`limitsMonthly:{1:…,2:…,increment:…}`).
  - ~17 questions (income, housing, county, and flags: pge, nid, vehicle, smog,
    pregnant, medicare, disability, student, homeless) vs the app's 5.
  - **67 provenance blocks**, `verifiedAt` dates spanning **2026-05-30 →
    2026-07-05** (current), all citing authoritative `.gov` sources
    (aspe.hhs.gov FPL guidelines, calfresh.dss.ca.gov, cdss.ca.gov, csd.ca.gov
    LIHEAP, bar.ca.gov CAP, headstart.gov, myfamily.wic.ca.gov, …) with
    `web.archive.org` snapshots for permanence.

**Conclusion:** the web corpus is large, professionally fact-checked, actively
maintained — and only available as a minified bundle with a schema that does not
match the app's. The *real* source of truth is the pipeline that generates the
site (repo or Notion CMS), not the bundle.

---

## 3. Current drift

| Dimension | App (shipped) | Web (live) |
|---|---|---|
| Provenance | `seed-sample` (un-verified) | verified, per-claim sources |
| Programs | 4 (county-local placeholders) | ~33 (67 provenance blocks) |
| Questions | 5 boolean/select | ~17 incl. FPL income tables |
| Newest verified | 2026-07-04 (placeholder) | 2026-07-05 (moves ~daily) |
| Schema | simplified string-provenance | array-of-claims + income tables |
| Program overlap | LIHEAP id only; otherwise **none** | — |

The app is not "slightly drifting" — it was **seeded and never replaced**. As the
team edits the web corpus, the gap only widens. Because the schemas differ, a
byte-for-byte copy is impossible and any ad-hoc transform risks **silently
dropping or mis-mapping a verified eligibility fact** — the exact failure the
"verified or silent" invariant forbids.

---

## 4. The sync mechanism (recommended + shipped step)

Considered four options:

| Option | Verdict |
|---|---|
| (a) Auto-scrape the web bundle → regenerate the asset | **Rejected.** Minified, content-hashed, schema-mismatched; a transform can silently corrupt verified facts. Violates the invariant. |
| (b) **CI drift-check** (detect + warn) | **Shipped.** Coarse fingerprint is robust to minification; detects staleness without touching content. |
| (c) **Documented manual sync** from a canonical export | **Recommended** for content — the only invariant-safe way to move verified rules. |
| (d) Shared source of truth (canonical JSON export) | **Ask TechEmpower for it** — the correct long-term fix (see §6). |

**What shipped in this PR (b):** `scripts/screener-drift-check.py` +
`.github/workflows/screener-drift-check.yml` (advisory). The checker fetches
`/qualify`, resolves the current chunk, extracts a **coarse fingerprint** (newest
`verifiedAt`, provenance-block count, question-id set, pipeline evidence), and
compares it to the bundled asset — warning when the app is on the seed or is
stale. It **never parses or copies rules**, so it cannot corrupt content.

### Run it

```bash
python3 scripts/screener-drift-check.py          # advisory, human-readable
python3 scripts/screener-drift-check.py --json    # machine-readable
python3 scripts/screener-drift-check.py --strict  # exit 1 on drift (for gating)
```

The CI workflow runs it advisory (never blocks a merge; only "Build APK" gates)
on PRs that touch the corpus/script, weekly, and on manual dispatch. Findings
appear as GitHub annotations + a step summary.

---

## 5. Manual content sync (when the production corpus is ready — #1586)

The screener surface + reader + drift-check are done; only the *content* is
pending. When TechEmpower delivers the verified corpus:

1. **Obtain the canonical export** — ideally a clean `screener_corpus.json` from
   the same pipeline the site consumes (see §6). Do **not** reverse-engineer the
   web bundle by hand.
2. **Map web schema → app schema** (v1). Per program, carry over: id, org,
   category, `name{en,es}`, `summary{en,es}`, `phone` (only if verified — else
   `null`), `applyUrl`, `criteria`. Collapse the web's `provenance[]` array into
   the app's single `verifiedDate` (use the **oldest** claim's `verifiedAt` for a
   program, so the footer never over-states freshness) and preserve the source
   in `sourceNote`. If a rule needs FPL income tables the v1 criteria model can't
   express, either extend the schema (bump `schemaVersion`) or omit the program —
   **never approximate** the threshold.
3. **Set `metadata.provenance = "techempower-verified"`** and
   `metadata.verifiedDate` to the corpus's own date. The seed banner disappears
   automatically.
4. **Sync `ScreenerProgramIds.kt`** constants + `seedIds` to the real ids
   (consumers: wallet #1514, autofill #1519).
5. **Fix the source-count copy** (CHANGELOG, #1517, any "N programs" strings) to
   the real number — see the Data-Safety serialization-point note; bump once.
6. **Verify:** `python3 scripts/screener-drift-check.py` should now report no
   `seed-still-shipping` / `stale-verified-date` findings. Run the airplane-mode
   acceptance check from #1517 (EN + ES bucketed results, no network requests).

---

## 6. The real fix: ask for a canonical corpus export

The robust long-term mechanism is a **shared source of truth**: TechEmpower
publishes the pipeline's output as a stable, versioned `screener_corpus.json`
(the app's schema, or a documented superset) — at a fixed URL or in a repo the
app can pull. Then sync becomes: download the canonical file → run the drift-check
→ (optionally) a `scripts/sync-screener.sh` that copies it verbatim into assets
and flips provenance. No scraping, no schema guessing, invariant preserved.

Until that export exists, keep the drift-check running and treat content sync as
the documented manual step above.

---

## Links

- #1517 — offline benefits screener (shipped surface)
- #1520 — benefits paperwork companion epic (invariants)
- #1586 — verified EN/ES benefits corpora (the pending content)
- #1605 — this sync mechanism
- Live: <https://techempower.org/qualify>
