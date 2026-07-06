#!/usr/bin/env python3
"""screener-drift-check — detect drift between the bundled offline screener
corpus and the live web screener at techempower.org/qualify.

WHY THIS EXISTS (issue #1605)
    Candela bundles an OFFLINE benefits screener (#1517): a "Do I qualify?"
    flow whose rules live in a bundled JSON asset
    (feature/src/main/assets/techempower/screener_corpus.json). The SAME rules
    live on techempower.org/qualify, which the team edits continuously. The two
    can silently drift as the web corpus is fact-checked and updated.

WHAT IT DOES (detect only — never authors content)
    Fetches /qualify, resolves the current client JS chunk, and extracts a
    COARSE FINGERPRINT (newest verified date, corpus size, question-id set,
    pipeline evidence). Compares it to the bundled asset's metadata and warns
    when the app is stale or is still shipping the un-verified seed sample.

WHY ONLY A FINGERPRINT (verified-or-silent invariant, epic #1520 #3)
    The web rules are baked into a minified, content-hash-named webpack bundle
    with a RICHER, DIFFERENT schema (array-of-claims provenance, FPL income
    tables). There is no published canonical JSON. This script deliberately does
    NOT parse or copy rules — a schema-mismatched transform could silently drop
    or mis-map a verified eligibility fact. It only detects "you are out of
    date" and tells a human. Regenerating the asset is a human step that
    consumes TechEmpower's canonical corpus export (see docs/screener-sync.md),
    not this bundle.

USAGE
    python3 scripts/screener-drift-check.py            # advisory, human output
    python3 scripts/screener-drift-check.py --json      # machine output
    python3 scripts/screener-drift-check.py --strict    # exit 1 if drift found

EXIT CODES
    0  in sync, or drift found in advisory (default) mode
    1  drift found AND --strict
    2  local error (asset missing / unparseable)
    3  could not reach the web screener AND --strict (advisory mode: exits 0)
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

QUALIFY_URL = "https://techempower.org/qualify"
CHUNK_RE = re.compile(r'/_next/static/chunks/pages/(qualify-[0-9a-f]+\.js)')
ASSET_REL = "feature/src/main/assets/techempower/screener_corpus.json"
UA = "candela-screener-drift-check (+https://github.com/techempower-org/candela/issues/1605)"


# ---------- fetch (curl first — Cloudflare-friendly — urllib fallback) ----------

def fetch(url: str, timeout: int = 20) -> str:
    try:
        out = subprocess.run(
            ["curl", "-sSL", "--max-time", str(timeout), "-A", UA, url],
            capture_output=True, text=True, timeout=timeout + 5,
        )
        if out.returncode == 0 and out.stdout:
            return out.stdout
    except (FileNotFoundError, subprocess.SubprocessError):
        pass
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 (https only)
        return r.read().decode("utf-8", "replace")


# ---------- web fingerprint (coarse, stable patterns only) ----------

def web_fingerprint() -> dict:
    html = fetch(QUALIFY_URL)
    m = CHUNK_RE.search(html)
    if not m:
        raise RuntimeError("could not locate qualify-<hash>.js chunk in /qualify HTML")
    chunk_url = f"https://techempower.org/_next/static/chunks/pages/{m.group(1)}"
    js = fetch(chunk_url)

    dates = sorted(set(re.findall(r'verifiedAt":"(\d{4}-\d{2}-\d{2})"', js)))
    question_ids = sorted(set(re.findall(r'\{id:"(q-[a-z0-9_\-]+)"', js)))
    return {
        "chunk": m.group(1),
        "newest_verified_at": dates[-1] if dates else None,
        "oldest_verified_at": dates[0] if dates else None,
        "verified_claim_count": len(re.findall(r'verifiedAt":"', js)),
        "provenance_block_count": len(re.findall(r'"provenance":\[', js)),
        "question_ids": question_ids,
        "question_count": len(question_ids),
        # sanity: confirms we actually parsed the fact-checked corpus, not a shell
        "has_pipeline_evidence": bool(re.search(r'"via":"[^"]*(wave|oracle|ep\d)', js)),
    }


# ---------- app fingerprint (bundled asset) ----------

def find_asset() -> Path:
    here = Path(__file__).resolve()
    for base in [here.parent.parent, *here.parents]:
        cand = base / ASSET_REL
        if cand.is_file():
            return cand
    raise FileNotFoundError(f"could not find {ASSET_REL} above {here}")


def app_fingerprint() -> dict:
    path = find_asset()
    corpus = json.loads(path.read_text(encoding="utf-8"))
    meta = corpus.get("metadata", {})
    programs = corpus.get("programs", [])
    questions = corpus.get("questions", [])
    return {
        "path": str(path),
        "provenance": meta.get("provenance"),
        "verified_date": meta.get("verifiedDate"),
        "schema_version": meta.get("schemaVersion"),
        "is_verified": meta.get("provenance") == "techempower-verified",
        "program_ids": sorted(p.get("id") for p in programs if p.get("id")),
        "program_count": len(programs),
        "question_ids": sorted(q.get("id") for q in questions if q.get("id")),
        "question_count": len(questions),
    }


# ---------- compare ----------

def compare(app: dict, web: dict) -> list[dict]:
    findings: list[dict] = []

    if not app["is_verified"]:
        findings.append({
            "level": "critical",
            "code": "seed-still-shipping",
            "msg": (f"app corpus provenance is {app['provenance']!r}, not "
                    f"'techempower-verified' — the app is still shipping the "
                    f"un-verified SEED sample ({app['program_count']} programs). "
                    f"The production corpus ({web['provenance_block_count']} "
                    f"verified provenance blocks on the web) has not landed "
                    f"(tracked by #1586)."),
        })

    aw, ww = app["verified_date"], web["newest_verified_at"]
    if aw and ww and aw < ww:
        findings.append({
            "level": "warn",
            "code": "stale-verified-date",
            "msg": (f"app verifiedDate {aw} is older than the web's newest "
                    f"verifiedAt {ww} — the web corpus has been fact-checked "
                    f"more recently."),
        })

    if web["has_pipeline_evidence"] and not app["is_verified"]:
        findings.append({
            "level": "info",
            "code": "schema-lineage-gap",
            "msg": ("web corpus carries per-claim provenance (source + "
                    "verifiedAt + pipeline 'via' tags); the app's simplified "
                    "schema does not. A regen must transform, not copy — see "
                    "docs/screener-sync.md."),
        })

    if web["question_count"] and app["question_count"] != web["question_count"]:
        findings.append({
            "level": "info",
            "code": "question-set-divergence",
            "msg": (f"app has {app['question_count']} screening questions; the "
                    f"web has {web['question_count']}. Different question sets "
                    f"produce different eligibility results."),
        })

    return findings


def main() -> int:
    ap = argparse.ArgumentParser(description="Detect app<->web benefits-screener drift.")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--strict", action="store_true", help="exit 1 on any drift")
    args = ap.parse_args()

    try:
        app = app_fingerprint()
    except (FileNotFoundError, json.JSONDecodeError, OSError) as e:
        print(f"ERROR reading bundled corpus: {e}", file=sys.stderr)
        return 2

    try:
        web = web_fingerprint()
    except Exception as e:  # noqa: BLE001 — network/parse; advisory tool
        note = f"could not fetch/parse web screener: {e}"
        if args.json:
            print(json.dumps({"ok": False, "note": note, "app": app}, indent=2))
        else:
            print(f"NOTE  {note}")
            print("      (advisory: skipping web comparison this run)")
        return 3 if args.strict else 0

    findings = compare(app, web)

    if args.json:
        print(json.dumps({
            "ok": len(findings) == 0,
            "app": app, "web": web, "findings": findings,
        }, indent=2))
    else:
        print("screener drift check — techempower.org/qualify  vs  bundled asset")
        print(f"  web : chunk {web['chunk']}  newest_verified {web['newest_verified_at']}  "
              f"{web['provenance_block_count']} provenance blocks  {web['question_count']} questions")
        print(f"  app : provenance {app['provenance']!r}  verified {app['verified_date']}  "
              f"{app['program_count']} programs  {app['question_count']} questions")
        print(f"  asset: {app['path']}")
        if not findings:
            print("\n  ✓ no drift detected — app corpus is verified and current.")
        else:
            print()
            for f in findings:
                tag = {"critical": "✗ CRITICAL", "warn": "! WARN", "info": "· INFO"}[f["level"]]
                print(f"  {tag}  [{f['code']}] {f['msg']}")
            # GitHub Actions annotations (harmless locally)
            for f in findings:
                gh = {"critical": "error", "warn": "warning", "info": "notice"}[f["level"]]
                print(f"::{gh} title=screener-drift::{f['code']}: {f['msg']}")

    return 1 if (findings and args.strict) else 0


if __name__ == "__main__":
    sys.exit(main())
