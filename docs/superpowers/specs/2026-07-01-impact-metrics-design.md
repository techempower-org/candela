# #1463 — Opt-in anonymous aggregate impact metrics

**Issue:** techempower-org/candela#1463 (`enhancement`, `priority:medium`)
**Branch:** `docs/1463-impact-metrics-spec`
**Author:** Luna
**Status:** 🟡 SPEC FOR REVIEW — no code ships from this branch. This document
exists to surface product/privacy decisions for JP **before** any
implementation. Nothing here is committed to until the open questions below are
answered.

---

## 0. Open questions for JP (decide these first)

These are the forks only JP can call. Everything downstream in this spec assumes
a **recommended default** (marked ✅) so the design is concrete — but each is a
real decision, not a foregone conclusion.

1. **Ship at all, given the privacy brand?** The app's headline promise is
   *"your stats never leave the phone"* (README §Features, privacy.md §3: *"No
   'anonymous usage statistics'"*). Even a perfectly-anonymous opt-in feature
   **forces a wording change to both**. Is the grant-reporting value worth
   editing the most-quoted privacy sentence Candela has? (If "no" — close #1463
   as wontfix and we stop here. This is the gate.)

2. **Delta vs. cumulative payload.** ✅ *Recommend per-period deltas* (this
   month's incremental hours/chapters), not lifetime totals — it makes the
   server sum trivially correct, and makes any single report reveal only one
   month of one device. Confirm, or state a reason to send cumulative.

3. **"Active devices" without a device ID.** ✅ *Recommend counting reports per
   calendar month* (one report per opted-in device per month ⇒ report-count ≈
   active devices) with **no persistent device identifier at all**. The only
   alternative that dedups perfectly is a rotating monthly nonce (§4.3) — a mild
   pseudo-identifier. Accept the "count the reports" approach, or require the
   nonce?

4. **Transport / who runs the collector.** ✅ *Recommend a tiny stateless HTTPS
   endpoint* (§5) rather than InstantDB — InstantDB auth is per-user
   (`as-token`), which would tie every report to a signed-in email and destroy
   anonymity. A new endpoint is "new infrastructure," which cuts against the
   least-infra preference — so: (a) stand up a minimal collector (Cloudflare
   Worker / small function), or (b) drop "active devices" and accept a weaker
   metric to avoid any server? Recommend (a).

5. **Source granularity.** Per-source finished-chapter counts are great for
   grants ("readers used 19 of 27 sources") but a rare source *combination* is a
   fingerprint. ✅ *Recommend reporting only the SET of source IDs used this
   period* (presence, not counts), coarsened. Confirm, or allow coarse-bucketed
   counts?

6. **Is DP-lite / noise overkill?** ✅ *Recommend "yes, overkill"* — coarse
   client-side rounding (§4.2) is proportionate; formal differential privacy
   adds math and a privacy-budget story for a dataset that's already unlinkable
   aggregate counts. Confirm we skip formal DP.

7. **Cadence + first-send friction.** ✅ *Recommend monthly, with a one-time
   "here's exactly what will be sent" preview before the very first report.*
   Confirm monthly (vs. weekly / on-demand).

8. **Deletion honesty.** Because reports carry no identifier, **already-sent
   aggregates cannot be located and deleted** — they're merged into sums with
   nothing pointing back to the device. This is a privacy *strength*
   (unlinkable) but must be stated plainly in consent copy and the privacy
   policy. OK to state "past contributions can't be individually withdrawn
   because they were never tied to you"?

---

## 1. Goals

- Give TechEmpower (the 501(c)(3) operating Candela) the **aggregate impact
  numbers grantmakers require**: total hours listened, chapters completed, books
  completed, breadth of sources used, and an active-device count — as
  *organization-wide sums*, never per-user.
- Make it **explicitly opt-in, default OFF**, with consent copy a lay reader
  understands and a **preview of the exact payload** before the first send.
- Keep the reported data **anonymous and unlinkable**: no email, no user ID, no
  device ID, no IP retention, no timestamps finer than a month.
- Reuse the **existing on-device stats pipeline** (#1235 `ListeningStats*`) as
  the source of truth — add no second recording pipeline, no new hot-path cost.
- Keep the **privacy policy and Play Data-Safety declaration truthful** after
  the change (a Data-Safety answer that contradicts observed traffic is a top
  Play rejection trigger — see `docs/data-safety-checklist.md` open item #4).

## 2. Non-goals (YAGNI / anti-goals)

- **No per-user analytics.** No per-install profile, no cohort, no funnel.
- **No retention / engagement tracking.** We do not measure whether *a* user
  came back; only how much reading happened across *everyone who opted in*.
- **No identifiers of any kind** in the payload — not email, not the InstantDB
  user id, not `ANDROID_ID`/`AD_ID` (the manifest doesn't even request AD_ID),
  not an install UUID (see §4.3 for the one contested nonce and why we avoid it).
- **No crash/diagnostic reporting.** Out of scope; different data type, different
  Data-Safety row.
- **No content, titles, URLs, or free text.** We never learn *what* was read,
  only coarse counts. Source identity is limited to the built-in source-plugin
  IDs (`royalroad`, `gutenberg`, …), never a URL or a self-hosted host name.
- **No change to the on-device stats dashboard's behavior** — it stays 100%
  local; impact sharing is a strictly additive, separately-gated layer on top.
- **No formal differential privacy** (see open question #6).
- **No always-on background upload.** Reporting piggybacks on app foreground; no
  new `WorkManager` wakeups beyond a lightweight monthly check while the app is
  open.

## 3. What already exists (so we build almost nothing new on-device)

`#1235` shipped a complete, private, on-device statistics pipeline. Everything a
grantmaker asks for is **already computed**:

| Grant metric | Already on device? | Source |
| --- | --- | --- |
| Hours listened (≈) | ✅ | `ListeningStats.totalEstimatedMs` (summed `durationEstimateMs` of finished chapters) |
| Chapters completed | ✅ | `ListeningStats.chaptersFinished` |
| Books completed | ✅ | `ListeningStats.booksCompleted` |
| Sources used | ✅ | `ListeningStats.perSource[].sourceId` |
| Active-device count | ❌ (needs a server to count) | derived from report volume (§4.3) |

- The snapshot is assembled by the **pure** `ListeningStatsCalculator.assemble()`
  from Room aggregates over tables the playback layer already writes
  (`chapter_history`, `playback_position`, `fiction`, `chapter`). No new entity,
  no migration.
- Access point: `ListeningStatsRepository.snapshot(now, zone): ListeningStats`
  (already injected; used today by `ListeningStatsScreen` and `PhoneWearBridge`).
- Time is explicitly an **estimate** (kdoc + UI "≈"). Impact numbers inherit the
  "≈" honesty and must be labeled "estimated" in grant output too.

**Implication:** the on-device work is (a) computing a *delta since last report*
and (b) shipping it. Almost all the new surface is the **consent UX**, the
**payload/anonymization design**, and the **collector endpoint** — not stats
plumbing.

## 4. What is counted, and how it's anonymized

### 4.1 The payload (per report, per opted-in device, per month)

A single report is a small JSON object of **coarse period deltas** — the reading
that happened *since the last successful report*:

```jsonc
{
  "schema": 1,
  "period": "2026-07",              // month granularity ONLY (no day/time)
  "app_version": "1.7",             // major.minor only — not the patch/build
  "hours_listened_bucket": 15,      // Δ estimated hours, rounded (see §4.2)
  "chapters_completed_bucket": 20,  // Δ finished chapters, rounded
  "books_completed_bucket": 5,      // Δ finished books, rounded
  "sources_used": ["royalroad", "gutenberg", "wikipedia"]  // SET, this period
}
```

What is deliberately **absent**: any id/email/token, IP (see §5), device model,
OS version, locale, exact timestamps, titles, URLs, per-source counts, and the
patch version. `app_version` is major.minor so a build hash can't narrow the
population.

### 4.2 Coarse rounding (proportionate, not DP)

Before sending, each numeric total is snapped to a coarse bucket **on-device**:

- hours → nearest 5 (0, 5, 10, 15, …)
- chapters → nearest 5
- books → nearest 1 (already coarse)

Rounding reduces the uniqueness of any single report (defends against a report
being "singled out" even without an id) while preserving aggregate accuracy at
org scale (rounding error averages out across many devices). This is the
**"DP-lite"** the issue hints at; §0-Q6 recommends this instead of formal DP.

### 4.3 Active devices without a persistent identifier — the contested bit

We want "how many devices are actively reading" without a device ID. Two designs:

- ✅ **Count reports per month (recommended, zero-identifier).** Each opted-in
  device sends **at most one report per calendar month** (client-side guard: a
  `lastReportedPeriod` DataStore key). The server counts *distinct reports in a
  month* — that count **is** the active-device number, with no identifier stored
  or sent. Risk: a device that reports twice in a month (reinstall, clock skew)
  double-counts. Mitigation: the client guard makes this rare; at org scale a
  handful of double-counts is within the "≈" we already disclose.
- ⚠️ **Rotating monthly nonce (only if perfect dedup is required).** A random
  UUID regenerated every period, sent with the report, used **server-side only
  to dedup within that period, then discarded**. It is *not* stable across
  months, so it can't track a device over time. This is a mild pseudo-identifier
  and adds a "Device or other IDs" flavor to the Data-Safety story — hence not
  recommended unless JP wants exact counts (§0-Q3).

### 4.4 Why deltas, not cumulative (the load-bearing choice)

Sending **per-period deltas** (not lifetime totals):

1. makes the server's job a plain `SUM` across all reports and all months → the
   org total is correct with no cross-time dedup;
2. makes any single intercepted/stored report reveal only **one month of one
   device**, not a lifetime fingerprint;
3. gives a clean revocation story — stop sending and no further deltas accrue.

Delta computation is local: `Δ = coarsen(current snapshot) − lastReportedTotals`,
where `lastReportedTotals` is a tiny DataStore record (the last cumulative values
we coarsened + sent). Never negative (clamp at 0 if the user cleared data).

## 5. Transport + server sketch

**Not InstantDB.** Its admin HTTP API authenticates as the signed-in user
(`as-token` impersonation, per `HttpInstantBackend` kdoc). Any write is therefore
attributable to an email — the opposite of anonymous — and we will not ship an
admin token in the APK. So sync's transport is unsuitable and reused nowhere here.

**Recommended: a tiny stateless collector.**

- **Endpoint:** `POST https://impact.techempower.org/v1/report` (or a path on an
  existing TechEmpower host / a Cloudflare Worker — least infra that can accept a
  POST and append a row).
- **Auth:** none. It's public and write-only. Abuse control = platform rate-limit
  + payload schema validation + a hard size cap. (There is nothing to steal: no
  reads, no identifiers.)
- **Server MUST NOT log or retain client IPs.** Configure the edge to strip/zero
  the source IP before any persistence. This is a **hard requirement** for the
  anonymity claim and must be documented (and, ideally, verifiable in the
  Worker source, which we can open-source alongside the app).
- **Stored row:** exactly the §4.1 payload fields + a server-side received-month
  stamp. No headers, no IP, no UA.
- **Frequency:** monthly, opportunistic — checked when the app is foregrounded;
  if `currentPeriod > lastReportedPeriod` and consent is ON, compute the delta
  and POST once.
- **Failure behavior:** fire-and-forget with a bounded retry *within the same
  period only*. If it never succeeds this month, we simply skip — a missed
  report is fine (under-count, never fabricate). **Do not** queue across months;
  a failed July report is dropped, not merged into August, to keep periods clean.
- **No response payload needed** beyond `204 No Content`.

**Output store → grant reporting (§7)** can be as simple as appending to a table
the org already controls; a monthly `SUM`/`COUNT DISTINCT period` query produces
the numbers.

## 6. Opt-in UX

### 6.1 Placement & consent model

- **Default OFF.** A new per-device DataStore flag `impact_sharing_opt_in`
  (NOT synced — a device-local choice, like the reading theme pref). Mirror the
  existing consent-ack precedent `SettingsViewModel.acknowledgeAiPrivacy()`.
- **Primary home:** a dismissible card on the **`ListeningStatsScreen`** (route
  `StoryvoxRoutes.STATS`) — the one screen where the user is already looking at
  exactly these numbers, so "share the org-wide version of this?" is in context.
- **Durable home:** a toggle in **Settings → About/Privacy** (next to the privacy
  policy link in `AboutSettingsScreen`) so it's findable and revocable forever,
  not just via a one-time card.
- **Preview before first send:** enabling opens a sheet showing the *exact*
  fields that would be sent this month (the real coarse numbers), a
  "What's shared / What's never shared" two-column disclosure, and a link to the
  privacy policy section. The user confirms from that sheet — consent is
  informed by the actual payload, not a description of it.

### 6.2 Copy draft (Library Nocturne tone, matches sync's voice)

> **Card title:** Help TechEmpower, anonymously
>
> **Body:** Candela is run by TechEmpower, a nonprofit. Grants that keep the app
> free need impact numbers — total hours listened, chapters finished, sources
> used — across everyone, never you specifically. You can share *anonymous,
> rounded monthly totals* to help. It's off by default.
>
> **What's shared:** coarse monthly totals (rounded), which built-in sources
> were used, and the app version. Nothing else.
>
> **What's never shared:** your name, email, device, location, IP, titles,
> links, or anything that could point back to you. No account required.
>
> **Note on withdrawal:** you can turn this off anytime and no more totals are
> sent. Totals already contributed can't be traced back to remove them —
> because they were never tied to you in the first place.
>
> **Primary button:** Preview what would be sent →
> **Secondary:** Not now

### 6.3 Revocation semantics

- Toggle OFF ⇒ the monthly check no-ops; no further reports. Immediate, local.
- `lastReportedPeriod` / `lastReportedTotals` are retained while OFF only so that
  re-enabling doesn't double-count the already-reported months; they hold no
  identity and can be cleared with app data.
- Already-sent aggregates: **not individually deletable** by design (§0-Q8) —
  disclosed in copy and privacy policy. (Contrast with sync's "Delete cloud
  data," which works precisely *because* sync data is keyed by the user.)

## 7. Grant-reporting output (what TechEmpower actually receives)

- **Not a dashboard v1.** Simplest honest deliverable: a **monthly CSV / one
  SQL view** over the collector store:
  `month, hours_listened, chapters_completed, books_completed, distinct_sources_used, active_devices`.
- `active_devices` = `COUNT(*)` of reports that month (§4.3).
- `hours_listened` etc. = `SUM` of the period-delta buckets that month; a
  cumulative-to-date figure is a running total of those sums.
- All figures labeled **"estimated (opt-in sample)"** in any grant document —
  they represent *only opted-in devices*, a lower bound on true usage, and time
  is the existing "≈" estimate. Over-claiming to a grantmaker is the failure mode
  to avoid.
- A tiny static dashboard (reuse the candela docs microsite pattern) is a
  possible **phase 3**, not needed for the first grant cycle.

## 8. Privacy policy + Data-Safety delta (exact edits)

This is the part that must be **surgically correct** — the whole feature lives or
dies on these staying truthful.

### 8.1 `README.md`
- Line ~50: *"…rendered as on-device charts — your stats never leave the phone."*
  → append: *"…never leave the phone — unless you explicitly turn on anonymous
  impact sharing (off by default), which sends only coarse, rounded, org-wide
  monthly totals with nothing that identifies you."*

### 8.2 `docs/privacy.md`
- **§2 ("What we collect")** — the sentence *"By default, Candela collects
  nothing"* stays TRUE (default OFF) but add a new subsection **§2.9 Anonymous
  impact sharing (optional, off by default)** describing the payload, coarse
  rounding, no-identifier / no-IP design, monthly cadence, and the
  non-withdrawable-past-contributions note.
- **§3 ("What we do NOT collect")** — the line *"No 'anonymous usage
  statistics'"* is now FALSE if we ship. Rewrite to:
  *"No analytics or tracking SDKs (no Firebase/GA/Mixpanel/Sentry), no
  fingerprinting, and no per-user usage collection. The one exception is the
  opt-in Anonymous Impact Sharing feature (off by default, §2.9), which — only
  if you enable it — sends coarse, rounded, unlinkable monthly totals to
  TechEmpower with no identifier of any kind."*
- **§4 (third-party table)** — add a row for the collector endpoint *(TechEmpower
  self-operated, not a third party)* or a new "First-party services" note; state
  IP is not logged.

### 8.3 `docs/data-safety-checklist.md` + Play Console
- This is a **new data flow** ⇒ re-audit per the checklist's own open item #4.
- **Likely declaration:** because the payload contains **no data type in Play's
  personal-data taxonomy** (no identifiers, no personal info, IP explicitly not
  retained), the defensible position is **no new "collected data type"** — with
  the reasoning documented, mirroring how OCR/BYOK are argued as "not collected."
  ⚠️ This is a JP call to confirm with the wizard open; the conservative
  alternative is declaring **App activity → Other actions** as *Optional*,
  *not shared with third parties* (TechEmpower is first-party), purpose
  *App functionality / Analytics*. Pick one and keep policy + form consistent.
- `docs/play-store-walkthrough.html` §IV Data-Safety table must be updated to
  match whichever declaration is chosen (listing-copy lock-step, per project
  memory on `listing/*.txt`).

## 9. Effort estimate by phase

| Phase | Scope | Rough size |
| --- | --- | --- |
| **0 — Decisions** | JP answers §0 (esp. Q1 ship-gate, Q4 collector) | — (blocks all) |
| **1 — Collector endpoint** | Stateless POST receiver, IP-stripping, schema validate, rate-limit, store; open-source the Worker | S–M (server, not app) |
| **2 — On-device delta + transport** | `ImpactReporter` (compute coarse delta from `ListeningStatsRepository.snapshot`, DataStore `lastReported*`, monthly foreground check, fire-and-forget POST, opt-in gate). Pure delta/rounding logic unit-tested. | M |
| **3 — Consent UX** | Opt-in flag + stats-screen card + Settings toggle + payload-preview sheet + "what's shared / never shared" copy; a11y (roles, live region). | M |
| **4 — Docs/legal** | README + privacy.md §2.9/§3 rewrite + Data-Safety re-audit + walkthrough §IV + Play Console change. | S (but must ship *with* the feature, not after) |
| **5 (optional) — Grant view** | SQL view / monthly CSV; later a static dashboard. | S |

**Sequencing note:** Phase 4 is not a follow-up — the docs/Data-Safety change
must land in the **same release** as the code, or observed traffic contradicts
the declaration (top Play rejection trigger). Phase 1 (server) and Phase 2–3
(app) can proceed in parallel once §0 is decided.

## 10. Risks & mitigations

- **Brand/trust risk.** Even anonymous, this edits the "never leaves the phone"
  promise. Mitigation: default OFF, payload preview, open-source collector,
  plain-language copy — and honoring §0-Q1 as a real gate.
- **Data-Safety mismatch → Play rejection.** Mitigation: §8.3 re-audit shipped
  with the feature; conservative declaration if in doubt.
- **Over-claiming to grantmakers.** Opt-in sample ≠ total usage. Mitigation:
  always label "estimated, opt-in sample; lower bound."
- **Re-identification via rare combos** (e.g., a lone device using 3 obscure
  sources in a tiny month). Mitigation: coarse rounding, source *set* not counts,
  month-only period, and — if a metric is too sparse to be safe — suppress it in
  output below a small-count threshold (k-anonymity-style floor).
- **Scope creep** toward per-user analytics. Mitigation: the non-goals in §2 are
  load-bearing; any future field addition re-triggers the §8.3 audit.

---

_Code refs: `core-data/.../repository/stats/ListeningStats.kt` (+`Calculator`),
`core-data/.../db/dao/ListeningStatsDao.kt`, `ListeningStatsRepository.snapshot`,
`feature/.../stats/ListeningStatsScreen.kt` (route `StoryvoxRoutes.STATS`),
`feature/.../settings/AboutSettingsScreen.kt` (privacy anchor),
`SettingsViewModel.acknowledgeAiPrivacy` (consent-ack precedent),
`core-sync/.../client/HttpInstantBackend.kt` (why InstantDB is per-user, unusable
for anonymous), `docs/privacy.md` §2–§4, `docs/data-safety-checklist.md`._
