# Notion OAuth setup (#1507)

Candela can connect to a user's private Notion workspace two ways:

1. **Connect Notion** — a one-tap OAuth flow (Chrome Custom Tab → consent →
   redirect back). The consent screen doubles as the page picker: whatever
   the user grants becomes browsable.
2. **Paste an Integration Token** — the original advanced fallback. Always
   available, needs no registration.

The OAuth path only lights up when this build carries **OAuth client
credentials**. This doc is what JP registers once to enable it. Until the
creds are present the Connect button is hidden and only the paste path
shows — **the app builds and CI is green with no creds** (empty-string
BuildConfig defaults, mirroring `INSTANTDB_APP_ID`).

---

## 1 · Register a public integration

Go to **https://www.notion.so/my-integrations** → **New integration**.

| Field | Value |
|---|---|
| **Type** | **Public** integration (this is what enables OAuth; an *internal* integration only issues a pasted token) |
| **Name / logo** | "Candela" (whatever you like — the user sees it on the consent screen) |
| **Capabilities** | **Read content** is enough. Candela only *reads* pages/databases; it never inserts, updates, or comments. Leave "Insert content" and "Update content" **off**, and "Read user information" off (we don't need the user's email — the token response's `workspace_name` is all the UI shows). |
| **Redirect URI** | `candela://oauth/notion` — **exactly** this, byte-for-byte. It must match the app's `AndroidManifest` intent-filter and `NotionOAuthConfig.REDIRECT_URI`. |

Notion is an **OAuth 2.0 confidential client** — after you save, it shows an
**OAuth client ID** and an **OAuth client secret**. You need both.

---

## 2 · Wire the credentials

Add **two lines** to `local.properties` (gitignored; lives only on JP's
machine + the self-hosted CI runner — never committed, never passed via
`-P`):

```properties
NOTION_OAUTH_CLIENT_ID=<the OAuth client ID>
NOTION_OAUTH_CLIENT_SECRET=<the OAuth client secret>
```

That's it. `app/build.gradle.kts` reads them at configure time into
`BuildConfig.NOTION_OAUTH_CLIENT_ID` / `_CLIENT_SECRET`. Rebuild → the
Connect button appears.

To confirm they took: the Browse → Notion manage sheet shows **Connect
Notion** (creds present) vs only the token-paste fields (creds absent).

---

## ⚠️ Security note — the client secret ships in the APK

Notion's token endpoint authenticates with **HTTP Basic
`client_id:client_secret`**; unlike Anthropic's public PKCE client, Notion
offers no public-client (no-secret) option. So a distributed APK carries the
secret, and **a determined user can extract it**. This is an accepted,
documented trade-off for the shippable slice:

- The secret only lets someone *initiate OAuth flows as "Candela"*; it does
  **not** grant access to any workspace — each grant still requires the
  user's explicit consent, and tokens are per-user.
- `local.properties` keeps the secret out of git and CI logs the same way
  `INSTANTDB_APP_ID` is handled, but that does **not** make it secret in the
  binary.
- **Future hardening (follow-up):** move the token exchange behind a small
  server-side proxy that holds the secret, so the APK never carries it.
  Tracked as a #1507 follow-up.

---

## How it works (for the curious)

1. **Connect Notion** → `SettingsRepositoryUi.beginNotionOAuth()` generates a
   random CSRF `state` nonce, persists it (encrypted, survives process
   death), and returns the authorize URL.
2. The sheet opens that URL in a **Chrome Custom Tab**. The user consents and
   picks the pages/databases to share.
3. Notion redirects to `candela://oauth/notion?code=…&state=…` (or
   `?error=access_denied` on cancel — handled gracefully, no crash).
4. `MainActivity` (singleTask → `onNewIntent`) hands the redirect to
   `NotionOAuthManager`, which **verifies the state nonce**, exchanges the
   code (`POST /v1/oauth/token`, Basic auth), and persists the access token
   (in the existing `notion.api_token` slot → the source's `Bearer` seam
   works unchanged), refresh token, and workspace name.
5. The Notion (PAT) source is **auto-enabled**, and its Browse chip now lists
   the granted content via `POST /v1/search`.

Token storage rides the same encrypted-prefs path as every other source
login (`storyvox.secrets`); with cloud sync on it's additionally
E2E-encrypted behind the user's passphrase. Notion's data-plane
`Notion-Version` header is **not** bumped by this feature.

**Refresh:** the refresh token is persisted and `NotionOAuthApi.refresh()`
is implemented, but the *trigger* (silent refresh-on-401 from the source
layer) is a follow-up — it needs a callback seam from `:source-notion` up to
`:app`. Until then, an expired grant surfaces as "reconnect".

---

## Data-safety lock-step

Adding this OAuth surface does **not** change Candela's Play Data-Safety
posture — the token is the user's own credential, content flows **user →
Notion directly**, and Candela shares nothing. The lock-step edits ship in
the same PR:

- `docs/data-safety-checklist.md` — §C sharing note (#1507 clarification).
- `docs/play-store-walkthrough.html` — §IV "API keys & tokens" row.
