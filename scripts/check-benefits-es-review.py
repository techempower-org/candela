#!/usr/bin/env python3
"""check-benefits-es-review — the ES half of the verified-or-silent gate (#1586).

The three TechEMPOWER benefits corpora in feature/src/main/assets/techempower/
(screener_corpus.json, notice_explainers.json, call_cards.json) carry EN + ES
content. The app shows a "sample data" banner until `metadata.provenance` is
"techempower-verified" (epic #1520, invariant 3). This check stops the Spanish
from being promoted to "verified" before a native speaker has reviewed it.

Rules, per corpus:
  1. Every bilingual object ({"en": ...}) has a non-blank "es" — no silent EN
     fallback on a benefits surface.
  2. `metadata.esReview` exists with a known `status`, and its `reviewSheet`
     file exists in the repo.
  3. If `provenance` is "techempower-verified", then `esReview.status` must be
     "native-speaker-reviewed" and carry `reviewedBy` + `reviewedDate`.

No network, no gradle — runs in seconds. Exit 0 = pass, 1 = violation,
2 = a corpus is missing or is not valid JSON.

Usage: python3 scripts/check-benefits-es-review.py [repo_root]
"""
from __future__ import annotations

import json
import pathlib
import sys

CORPORA = ("screener_corpus.json", "notice_explainers.json", "call_cards.json")
ASSET_DIR = pathlib.Path("feature/src/main/assets/techempower")
PENDING = "needs-native-speaker-review"
REVIEWED = "native-speaker-reviewed"
VERIFIED = "techempower-verified"


def bilingual_gaps(node, path="$"):
    """Yield JSON paths of {"en": ...} objects whose "es" is missing or blank."""
    if isinstance(node, dict):
        if "en" in node and isinstance(node["en"], str):
            es = node.get("es")
            if not isinstance(es, str) or not es.strip():
                yield path
        for k, v in node.items():
            yield from bilingual_gaps(v, f"{path}.{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from bilingual_gaps(v, f"{path}[{i}]")


def check(root: pathlib.Path) -> int:
    errors: list[str] = []
    for name in CORPORA:
        p = root / ASSET_DIR / name
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(f"::error file={ASSET_DIR / name}::cannot read corpus: {e}")
            return 2
        meta = data.get("metadata", {})
        for gap in bilingual_gaps(data):
            errors.append(f"{name}: {gap} has no Spanish (es)")
        review = meta.get("esReview")
        if not isinstance(review, dict):
            errors.append(f"{name}: metadata.esReview is missing")
            continue
        status = review.get("status")
        if status not in (PENDING, REVIEWED):
            errors.append(f"{name}: esReview.status {status!r} is not {PENDING!r} or {REVIEWED!r}")
        sheet = review.get("reviewSheet")
        if not sheet or not (root / sheet).is_file():
            errors.append(f"{name}: esReview.reviewSheet {sheet!r} does not exist")
        if meta.get("provenance") == VERIFIED:
            if status != REVIEWED:
                errors.append(
                    f"{name}: provenance is {VERIFIED!r} but the Spanish is still {status!r} — "
                    f"a native speaker must sign off first (see {sheet})"
                )
            for field in ("reviewedBy", "reviewedDate"):
                if status == REVIEWED and not review.get(field):
                    errors.append(f"{name}: esReview.{field} is required once status is {REVIEWED!r}")
        print(f"{name}: provenance={meta.get('provenance')!r} esReview.status={status!r}")
    for e in errors:
        print(f"::error title=benefits-es-review::{e}")
    if errors:
        return 1
    print("benefits ES review gate: ok")
    return 0


def _strings(node, path):
    """Yield (path, en, es) for every bilingual object, keyed by id/formNumber."""
    if isinstance(node, dict):
        if isinstance(node.get("en"), str):
            yield path, node["en"], node.get("es") or ""
            return
        for k, v in node.items():
            if k != "metadata":
                yield from _strings(v, f"{path}.{k}" if path else k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            key = (v.get("id") or v.get("formNumber")) if isinstance(v, dict) else None
            yield from _strings(v, f"{path}[{key if key else i}]")


def checklist(root: pathlib.Path) -> int:
    """Print the reviewer table (every EN/ES pair) as Markdown."""
    esc = lambda s: s.replace("|", "\\|")  # noqa: E731
    for name in CORPORA:
        data = json.loads((root / ASSET_DIR / name).read_text(encoding="utf-8"))
        rows = list(_strings(data, ""))
        print(f"\n### `{name}` — {len(rows)} strings\n")
        print("| ✓ | Path | EN | ES (draft) |\n|---|---|---|---|")
        for p, en, es in rows:
            print(f"| ☐ | `{p}` | {esc(en)} | {esc(es)} |")
    return 0


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    repo = pathlib.Path(args[0] if args else ".").resolve()
    sys.exit(checklist(repo) if "--checklist" in sys.argv else check(repo))
