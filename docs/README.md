<!-- Dev-facing index of docs/. Excluded from the published Jekyll site
     (see _config.yml `exclude:`) — GitHub renders it when you browse /docs. -->

# Candela docs

This folder is the **canonical source** for Candela's user-facing site, its
GitHub wiki, and (in part) its Play Store listing. Edit the docs here and push
to `main`; the surfaces below regenerate from these files. Don't hand-edit the
wiki or the microsite output — you'll be overwritten.

## Where these docs surface

| Surface | Built from | How |
|---|---|---|
| **Microsite** — [candela.techempower.org](https://candela.techempower.org) | `index.md` + the `*.md` pages here (minus `exclude:` in `_config.yml`) | GitHub Pages / Jekyll on push to `main`. Version + "what's new" are hydrated **live via JS** from the Releases API, so they never restale. |
| **GitHub wiki** | the allowlisted pages in [`scripts/wiki/sync_wiki.py`](../scripts/wiki/sync_wiki.py) `PAGE_MAP` | `.github/workflows/wiki-sync.yml` on push to `main` touching `docs/**`. Marker-guarded: never clobbers a hand-authored wiki page. To add a page to the wiki, add it to `PAGE_MAP` — don't create it in the wiki. |
| **Wiki `Home` (release section)** | `CHANGELOG.md` | `.github/workflows/release-docs-sync.yml` on tag push. Source/module counts are recomputed live from `settings.gradle.kts` + the `source-*` tree (#1146) — self-healing, not hand-maintained. |
| **Play Store listing** | [`../app/src/main/play/listings/en-US/*.txt`](../app/src/main/play/listings/en-US) (mirrored to `play-store/listing/*.txt`) | Uploaded by Gradle Play Publisher. **This is the canonical listing copy — edit those `.txt` files, not the planning docs below.** Keep `README.md` / `index.md` / `faq.md` copy consistent with it. |

## For users (published microsite pages)

- [`index.md`](index.md) — the marketing landing page (Jekyll site root).
- [`install.md`](install.md) — sideload guide, system requirements, Royal Road sign-in, update path.
- [`faq.md`](faq.md) — Google Drive (two ways), Google Keep (`wontfix`), and more.
- [`accessibility.md`](accessibility.md) — TalkBack, contrast, dyslexia fonts, reduced motion.
- [`voices.md`](voices.md) — the neural voice catalog and refresh workflow.
- [`sync.md`](sync.md) — optional InstantDB cloud sync (what syncs, encryption, deletion).
- [`privacy.md`](privacy.md) — the privacy policy. **Serialization point — coordinate edits.**

### Per-source setup guides (BYOK / OAuth)

- [`reddit-setup.md`](reddit-setup.md) — installed-app client id for the reddit source (#1492).
- [`google-drive-setup.md`](google-drive-setup.md) — `drive.file` OAuth for the Google Drive source (#1496).
- [`notion-oauth-setup.md`](notion-oauth-setup.md) — Notion integration token / OAuth (#1507).

## For contributors

- [`architecture.md`](architecture.md) — module graph, dependency roles, plugin seam.
- [`CONTRIBUTING-SOURCES.md`](CONTRIBUTING-SOURCES.md) — add a fiction source (~30 min to a green contract test).
- [`CONTRIBUTING-VOICES.md`](CONTRIBUTING-VOICES.md) — add a TTS voice engine.
- [`compose-gotchas.md`](compose-gotchas.md) — Jetpack Compose pitfalls hit in this codebase.
- [`ROADMAP.md`](ROADMAP.md) — long-form roadmap and backlog.

## Release & Play Store operations

- [`play-store-readiness.md`](play-store-readiness.md) — the launch checklist (#1302).
- [`play-console-prerequisites.md`](play-console-prerequisites.md) — Play Console upload prerequisites.
- [`play-store/RUNBOOK.md`](play-store/RUNBOOK.md) — step-by-step upload runbook.
- [`play-store-policy-check.md`](play-store-policy-check.md) — policy compliance review.
- [`play-store-signing.md`](play-store-signing.md) / [`release-keystore.md`](release-keystore.md) — app-signing & keystore migration.
- [`data-safety-checklist.md`](data-safety-checklist.md) — Data Safety declaration + Play Console checklist (#1139). **Serialization point.**
- [`play-store-screenshots.md`](play-store-screenshots.md) / [`screenshots.md`](screenshots.md) — screenshot capture plan + inventory.
- [`slack-release-template.md`](slack-release-template.md) — the #candela release-announcement template.
- `play-store-walkthrough.html`, `play-store/feature-graphic.html`, `instantdb-email-template.html` — generated/asset HTML.

## Testing & reference

- [`backend-test-results.md`](backend-test-results.md) — per-source backend probe results.
- [`testing/SEED.md`](testing/SEED.md) — the deterministic reader seed.

## Design specs (historical)

`superpowers/specs/` holds the original per-feature design docs (excluded from
the published site). They read as a thread of what shipped — see the
["Design specs" list in the root README](../README.md#architecture).

---

_Counts current at v1.10.0: **33 registered sources** across 32 modules
(`source-notion` registers two), **46 Gradle modules**, four on-device neural
voice families. The live source/module counts are recomputed by the wiki
release-sync (#1146); the Play listing is the canonical marketing count._
