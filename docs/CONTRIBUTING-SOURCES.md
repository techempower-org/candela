# Adding a source to Candela

A **source** turns some corpus of text (a website, an API, a feed) into
fictions and chapters that Candela narrates. Adding one is a self-contained,
~30-minute task: scaffold a module, implement one HTTP client against your
service, map the responses into the shared models, and turn the contract test
green. There are **two** central edits at the very end and no others — no
touching a giant `when`, no editing the settings screen, no `SourceIds` entry.

Reference source (living documentation): **`source-hackernews`** — a small,
complete JSON-backed source. When this guide says "see how a real source does
it," open that module.

## Prerequisites

- **JDK 17.** That's it. You do **not** need to build the whole app to develop a
  source — everything you write is exercised by a module-scoped unit test
  (`:source-<id>:testDebugUnitTest`), which is fast and needs no device.
- Familiarity with Kotlin coroutines and OkHttp. No Hilt/KSP knowledge needed —
  the annotation does the wiring.

## 1. Scaffold the module

```bash
scripts/new-source.sh <id> "<Display Name>"
# e.g.
scripts/new-source.sh demofeed "Demo Feed"
```

`<id>` is the stable, lowercase identifier (letters/digits, e.g. `demofeed`);
`"<Display Name>"` is what the user sees. This generates:

```
source-<id>/
  build.gradle.kts                       # android-library + hilt + ksp + the test kit
  src/main/.../<Name>Source.kt           # @SourcePlugin, FictionSource stubbed with NotFound
  src/main/.../net/<Name>Api.kt          # IO-pinned request() wrapper, status mapping pre-written
  src/test/.../<Name>ContractTest.kt     # subclass of the shared contract kit
```

Everything compiles immediately; the contract test **fails honestly** because
the stub doesn't talk to the network yet. That red test is your to-do list.

### Scaffold modes

`new-source.sh` scaffolds an **anonymous JSON HTTP** source by default. Pass one
mode flag (mutually exclusive) when your backend has a different shape:

| Flag               | Backend shape                                       | What changes vs. the default                                                                                          |
|--------------------|-----------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| *(none)*           | Anonymous JSON HTTP — `source-hackernews`           | —                                                                                                                   |
| `--auth`/`--oauth` | OAuth / BYOK token HTTP — `source-reddit`, `source-google-drive` | `request()` threads a Bearer token via an `accessToken()` seam; the contract test wires a non-blank fake token (#1529) |
| `--xml`/`--feed`   | XML / Atom / RSS HTTP — `source-arxiv`, `source-rss`, `source-primegaming` | XML `Accept` header, no kotlinx-serialization, Atom-bodied contract fixture (#1533)                                  |
| `--local`          | Non-HTTP local provider — `source-ocr`, `source-epub`, `source-calendar` | a `<Name>Reader` device-read seam, no OkHttp/serialization, a plain fake-backed test — no HTTP contract kit (#1526)  |

An authed **XML** feed is the one combination the flags don't cover directly:
start from `--auth` and swap the `Accept` header (and drop the JSON parse-error
catch), or start from `--xml` and add the `accessToken()` seam. See
**Local-provider sources** below for the `--local` path in full.

## 2. Implement `net/<Name>Api.kt`

The scaffold ships a `request()` wrapper that already does the two things every
review checks for: it pins the blocking OkHttp call to `Dispatchers.IO` (#585)
and maps HTTP status codes to typed `FictionResult` failures. **Copy that shape
for every endpoint** — never surface a raw HTTP code, a raw body, or a thrown
exception to the source layer.

Point your endpoints at the real service and add typed request functions
(`popular`, `search`, a detail fetch, a chapter fetch). The `baseUrl` seam is
already `open` so the contract test can retarget it at a MockWebServer.

> **Authenticated source (`--auth`)?** The scaffolded `request()` reads the
> bearer token from an `open suspend fun accessToken()` seam and short-circuits
> to `AuthRequired` when it's blank — wire `accessToken()` to your token store.
> The `--auth` contract test overrides `accessToken()` with a **non-blank fake**
> so `popular()` actually reaches the wire; without it a blank-token
> short-circuit issues zero requests and the IO-pin check fails confusingly with
> "no request reached the probe" (#1529).

> **Feed / XML source (`--xml`)?** The scaffolded `request()` sends an XML
> `Accept` header and has **no** kotlinx-serialization parse-error catch (regex
> and `XmlPullParser` don't throw `SerializationException`). Parse in your
> `parse` lambda; feed sources usually add conditional-GET headers
> (`If-None-Match` / `If-Modified-Since`) here. `source-arxiv` and `source-rss`
> are two real XML precedents (#1533).

### `FictionResult` decision table

Every network call must resolve to exactly one of these. The scaffolded
`request()` already implements this table — keep it intact as you extend it:

| Upstream                         | Return                              |
|----------------------------------|-------------------------------------|
| 2xx + parseable body             | `FictionResult.Success(value)`      |
| 401 / 403 (no CF challenge)      | `FictionResult.AuthRequired(...)`   |
| 404                              | `FictionResult.NotFound(...)`       |
| 429                              | `FictionResult.RateLimited(retryAfter, ...)` |
| Cloudflare challenge body/headers| `FictionResult.Cloudflare(challengeUrl, ...)` |
| any `IOException` / 5xx          | `FictionResult.NetworkError(msg, cause)` |

Check for a Cloudflare challenge **before** the 401/403 arm — a CF-gated 403
must read as `Cloudflare` (escalate to the WebView resolver), not "sign in
required." See `Ao3Api.requestText` and `StandardEbooksApi.fetchString` for two
real CF-aware implementations.

## 3. Map responses into the shared models

In `<Name>Source.kt`, replace each `NotFound("not implemented")` with a call
that fetches via your Api and maps the result into the core-data models:

- **Browse** (`popular` / `latestUpdates` / `byGenre` / `search`) →
  `ListPage<FictionSummary>` (set `hasNext` so pagination terminates).
- **`fictionDetail`** → `FictionDetail` (synopsis + chapter list, no bodies).
- **`chapter`** → `ChapterContent` (cleaned/sanitized text).
- **`genres`** → the genre labels your `byGenre` accepts (or `Success(emptyList())`).
- **Auth-gated** (`followsList` / `setFollowed`) → return
  `AuthRequired` when anonymous; only implement if your service has a real
  account-side follow concept (and set `supportsFollow = true`).

Use `FictionResult.map { … }` to transform a `Success` while passing failures
through unchanged. `source-hackernews` (`toSummary()` / `toDetail()`) is the
canonical mapping example.

## 4. Turn the contract test green

Open `<Name>ContractTest.kt` and set the two fixture hooks:

- **`happyListBody()`** — a trimmed, real response body from your list endpoint
  (2–3 items is plenty).
- **`listPathFragment()`** — a substring of the path your `popular()` hits, so
  the kit routes the happy body to it.

Then run it:

```bash
./gradlew :source-<id>:testDebugUnitTest --tests '*ContractTest'
```

The kit enforces the tribal gotchas as executable checks:

- **network work leaves the caller thread** (the #585 `Dispatchers.IO` pin),
- **401/403 → `AuthRequired`, 429 → `RateLimited`** (never raw exceptions),
- **a Cloudflare challenge body is a failure, never returned as chapter text**,
- **blank search doesn't crash**.

If your source is search-only (no meaningful `popular()`), set
`override val exercisesPopular = false` and the popular-based checks skip.
Purely local (non-HTTP) sources don't use this kit at all — scaffold them with
`--local` and see **Local-provider sources** below.

## 5. The two edits that finish the module

The scaffold prints these; they are the only central changes:

```kotlin
// settings.gradle.kts
include(":source-<id>")

// app/build.gradle.kts
implementation(project(":source-<id>"))
```

That's it. `@SourcePlugin(id = …)` makes KSP emit **both** Hilt bindings — the
registry descriptor (Browse chip, Settings auto-section) and the
`Map<String, FictionSource>` routing entry. You do **not** add a `SourceIds`
constant (that table is frozen — the annotation `id` is the source of truth) and
you do **not** hand-write a DI module.

## Local-provider sources (non-HTTP)

Some sources read the **device**, not a network service — `source-ocr` (scanned
text), `source-epub` (SAF-picked books), `source-calendar` (`CalendarContract`).
They have no HTTP surface, so the OkHttp `request()` wrapper, the qualified-client
DI module, and the HTTP contract kit don't apply. Scaffold them with `--local`:

```bash
scripts/new-source.sh --local <id> "<Display Name>"
```

That emits a leaner module:

- **no** OkHttp / kotlinx-serialization and **no** `core-source-testkit` dep;
- a `<Name>Reader` seam — a concrete `@Inject`-constructor class (so Hilt builds
  it with no hand-written DI module) whose IO-pinned `items()` / `item()` methods
  you point at your device read (SAF, `ContentResolver`, or a DataStore the
  `:app` / `:feature` layer persists);
- a plain `<Name>SourceTest` with a hand-rolled `FakeReader` (the `source-ocr`
  `OcrSourceTest` pattern) instead of a contract-kit subclass;
- an `AndroidManifest.xml` placeholder for a permission you may need to declare
  (e.g. `READ_CALENDAR`) — delete it if your source needs none.

The IO pin the HTTP kit enforces automatically is **your** responsibility in a
local source: keep every blocking device read inside
`withContext(Dispatchers.IO)` in the reader. `source-ocr` is the reference
implementation. If you ran the default scaffold before realizing your source is
local, re-run with `--local` on a fresh id rather than gutting the HTTP scaffold
by hand.

## PR checklist

- [ ] `:source-<id>:testDebugUnitTest` (the contract test) is green.
- [ ] CI **Build APK** passes — this is the compile gate and proves your
      `@SourcePlugin` bindings resolve in the `:app` Hilt graph.
- [ ] Every network call is `withContext(Dispatchers.IO)` (IO pin).
- [ ] Status codes map per the decision table (auth/rate/CF are typed, not
      `NetworkError`).
- [ ] No `SourceIds` edit; the `@SourcePlugin` `id` is the identity.
- [ ] `description` and `sourceUrl` are set on `@SourcePlugin` (they render in
      the plugin manager card).

Reviewers look for exactly those six things.
