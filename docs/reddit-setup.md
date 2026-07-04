# Reddit setup (#1492)

Candela reads Reddit through Reddit's **official OAuth JSON API**, not the
`.rss` feeds. Reddit returns an instant HTTP 429 to Candela's honest
User-Agent on `.rss` (verified 2026-07-02, #1489) — a deliberate policy
pushing clients onto the API — and even when a feed loads, its items are
truncated stubs. The API returns **full post bodies** and is sanctioned to
**≈100 requests/minute per client id**.

Reddit is **BYOK** (bring-your-own-key): you create your own free Reddit
**installed app** and paste its **client id** into Candela. An installed
app has a client id and **no client secret**, so Candela ships **no secret
of any kind** — the cleanest possible posture for a distributed app. The
token Candela mints is *userless* and **read-only**: it can read public
subreddits and posts, and can do nothing to your account.

> **Status (v1):** shipped end to end. Enable Reddit in **Settings →
> Library & Sync → Plugin Manager → Reddit**, then paste your client id in
> the **Reddit card under Settings → Library & Sync** (client-id field +
> "append top comments" toggle). Until a client id is entered the source
> is inert (every call returns "sign-in required"). A *generic*
> BYOK-config-field seam (so credentialed sources don't each hand-write a
> settings row) is tracked separately in
> [#1531](https://github.com/techempower-org/candela/issues/1531).

---

## 1 · Create a Reddit "installed app"

You need a free Reddit account. Then:

1. Go to **https://www.reddit.com/prefs/apps** (Settings → *(you're at the
   bottom)* → **Apps**, or the direct URL).
2. Click **"Create App"** / **"Create Another App"** at the bottom.
3. Fill in:

| Field | Value |
|---|---|
| **name** | `Candela` (anything you like — only you see it) |
| **type** | **installed app** — this is the important one. It creates a *public* client with a client id and **no secret**. (*Not* "web app" or "script".) |
| **description** | optional |
| **about url** | optional |
| **redirect uri** | `http://localhost` — required by the form, but **unused**: Candela's userless grant never redirects. Any valid URL is fine. |

4. Click **"Create app"**.

---

## 2 · Copy your client id

After creating the app, Reddit shows a card. The **client id** is the short
string **directly under the app name** (under the words *"installed app"*) —
it looks like `AbC1dEf2GhI3jK`. There is **no secret** for an installed app,
so that's the only value you need.

Paste that client id into Candela: **Settings → Library & Sync → Reddit →
Client id → Save**. That's it — Candela mints a read-only token in the
background and Reddit browsing lights up. (Enable the Reddit plugin first in
the same section's Plugin Manager if you haven't.)

---

## 3 · How Candela reads Reddit

- **A subreddit is a "book."** `r/nosleep`, `r/books`, `r/WritingPrompts` —
  each becomes a fiction in your library.
- **A post is a "chapter."** Its **full self-text** is the chapter body
  (never a stub). Link/image posts degrade gracefully to a short "this is a
  link post" note.
- **Top comments (optional).** Turn on *"Append top comments"* and the top N
  comments narrate as an epilogue after each post — great for discussion
  threads. Default is off.
- **Which posts?** A subreddit's chapters are its **hot** posts (this
  realises the issue's "popular = hot posts" idea). The sort (hot / new /
  top) is a per-source preference with a sensible **hot** default; a UI knob
  to change it is a follow-up.

### Discovery

| Browse action | What you get |
|---|---|
| **Search** | subreddits matching your query (`/subreddits/search`) |
| **Popular** | trending subreddits (`/subreddits/popular`) |
| **Latest** | newest subreddits (`/subreddits/new`) |
| **Genres** | a curated set of reading-friendly subreddits (books, nosleep, WritingPrompts, …) |

> Discovery lists surface the first ~25 subreddits, and a subreddit's
> chapter list is capped at its first 100 posts (Reddit paginates by cursor,
> not page number — deeper paging is a follow-up). Genres shows a curated
> default set today; deriving it from your *subscribed* subreddits needs a
> full user sign-in (the userless grant can't do that) — a follow-up, as is
> a UI to edit a personal favourites list.

---

## ⚠️ Security & privacy note

- **No secret ships.** An installed-app client id is a *public* identifier,
  not a credential — there is nothing secret in the APK. (It is still stored
  encrypted in `storyvox.secrets`, for parity with other source logins.)
- **Read-only, userless.** The `installed_client` grant produces a token
  scoped to public reads only. Candela cannot post, vote, or see your
  private data with it.
- **Honest User-Agent.** Every request carries Candela's descriptive UA
  (#1204). No browser-UA spoofing — the descriptive UA is the whole reason
  the API path exists (RSS blocks it; the API welcomes it).
- **Your quota is yours.** Rate limits attribute to *your* client id
  (≈100 QPM). A stable per-install device id keeps that attribution clean.

---

## How it works (for the curious)

1. Candela `POST`s to `https://www.reddit.com/api/v1/access_token` with HTTP
   Basic auth (`<client id>:` — empty password) and
   `grant_type=https://oauth.reddit.com/grants/installed_client&device_id=<uuid>`.
2. Reddit returns a bearer token (~1 hour TTL, read-only). Candela caches it
   in memory and renews it a minute before expiry.
3. Every read hits `https://oauth.reddit.com/...` with
   `Authorization: bearer <token>` — subreddit search / popular / new,
   `/r/<sub>/{hot,new,top}` for a subreddit's posts, and
   `/r/<sub>/comments/<id>` for a post's full body + comments.

Only the client id + a generated device id are persisted; the bearer token
is never written to disk (it's cheap to re-mint and expires hourly).
