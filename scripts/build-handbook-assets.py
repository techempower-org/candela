#!/usr/bin/env python3
"""Compile docs/ into the Candela Handbook's bundled assets.

The in-app handbook (:source-handbook) ships its content as assets so the docs
stay canonical and the handbook can't drift silently. This script snapshots a
fixed set of docs/ pages into narration-clean plain text and writes them to
source-handbook/src/main/assets/handbook/, stamped with the app versionName.

Output:
  assets/handbook/manifest.tsv   version line + one `id<TAB>title` per chapter
  assets/handbook/<id>.txt       narration-ready plain-text body per chapter

Usage:
  scripts/build-handbook-assets.py           # regenerate the committed assets
  scripts/build-handbook-assets.py --check   # fail (exit 1) if committed assets
                                             # are stale vs docs/ — the drift guard

Pure-stdlib (no deps), matching the repo convention. The markdown→text pass is
deliberately conservative: it strips syntax that narrates badly (code fences,
tables, link URLs, heading/emphasis markers) and keeps prose + list items.
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path

# Ordered handbook chapters: (chapter id/slug, display title, source doc).
# Slugs must match [a-z0-9-]+ (the reader guards against anything else).
CHAPTERS: list[tuple[str, str, str]] = [
    ("getting-started", "Getting Started", "docs/install.md"),
    ("voices", "Voices", "docs/voices.md"),
    ("reader-accessibility", "The Reader & Accessibility", "docs/accessibility.md"),
    ("sync-and-privacy", "Sync & Privacy", "docs/sync.md"),
    ("reddit-setup", "Connecting reddit", "docs/reddit-setup.md"),
    ("google-drive-setup", "Connecting Google Drive", "docs/google-drive-setup.md"),
    ("notion-setup", "Connecting Notion", "docs/notion-oauth-setup.md"),
    ("faq", "Frequently Asked Questions", "docs/faq.md"),
]

ASSET_SUBDIR = "source-handbook/src/main/assets/handbook"

FRONT_MATTER_RE = re.compile(r"\A---\n.*?\n---\n", re.S)
HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.S)
FENCE_RE = re.compile(r"^```")
IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
LINK_RE = re.compile(r"\[([^\]]+)\]\([^)]*\)")
INLINE_CODE_RE = re.compile(r"`([^`]+)`")
BOLD_RE = re.compile(r"\*\*([^*]+)\*\*")
ITALIC_RE = re.compile(r"(?<!\*)\*([^*]+)\*(?!\*)|_([^_]+)_")
HEADING_RE = re.compile(r"^#{1,6}\s+")
LIST_RE = re.compile(r"^\s*[-*+]\s+")
ORDERED_LIST_RE = re.compile(r"^\s*\d+\.\s+")
BLOCKQUOTE_RE = re.compile(r"^\s*>\s?")
TABLE_SEP_RE = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$")


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def markdown_to_text(md: str) -> str:
    """Conservative markdown → narration-ready plain text."""
    md = FRONT_MATTER_RE.sub("", md, count=1)
    md = HTML_COMMENT_RE.sub("", md)

    out: list[str] = []
    in_fence = False
    for raw in md.splitlines():
        if FENCE_RE.match(raw.strip()):
            in_fence = not in_fence
            # Drop code fences entirely — they narrate as noise.
            continue
        if in_fence:
            continue

        line = raw.rstrip()

        # Table separator rows (|---|---|) vanish; other table rows become prose.
        if TABLE_SEP_RE.match(line):
            continue
        if line.strip().startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            line = " — ".join(c for c in cells if c)

        line = BLOCKQUOTE_RE.sub("", line)
        line = HEADING_RE.sub("", line)
        line = LIST_RE.sub("", line)
        line = ORDERED_LIST_RE.sub("", line)

        line = IMAGE_RE.sub("", line)
        line = LINK_RE.sub(r"\1", line)
        line = INLINE_CODE_RE.sub(r"\1", line)
        line = BOLD_RE.sub(r"\1", line)
        line = ITALIC_RE.sub(lambda m: m.group(1) or m.group(2), line)

        out.append(line)

    text = "\n".join(out)
    # Collapse 3+ blank lines to a single blank-line paragraph break.
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip() + "\n"


def read_version(root: Path) -> str:
    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    return m.group(1) if m else "0.0.0"


def build(root: Path) -> dict[str, str]:
    """Return {relative asset path: content} for the whole handbook bundle."""
    version = read_version(root)
    files: dict[str, str] = {}
    manifest_lines = [f"version\t{version}"]
    for slug, title, doc in CHAPTERS:
        src = root / doc
        if not src.exists():
            print(f"::error:: handbook source doc missing: {doc}", file=sys.stderr)
            raise SystemExit(2)
        body = markdown_to_text(src.read_text(encoding="utf-8"))
        files[f"{slug}.txt"] = body
        manifest_lines.append(f"{slug}\t{title}")
    files["manifest.tsv"] = "\n".join(manifest_lines) + "\n"
    return files


def main(argv: list[str]) -> int:
    check = "--check" in argv[1:]
    root = repo_root()
    asset_dir = root / ASSET_SUBDIR
    files = build(root)

    if check:
        stale: list[str] = []
        for name, content in files.items():
            path = asset_dir / name
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                stale.append(name)
        # Also flag orphaned committed assets not in the current build.
        if asset_dir.exists():
            expected = set(files)
            for path in asset_dir.iterdir():
                if path.is_file() and path.name not in expected:
                    stale.append(f"{path.name} (orphaned)")
        if stale:
            print(
                "::error:: handbook assets are stale — run "
                "scripts/build-handbook-assets.py. Drifted: " + ", ".join(sorted(stale)),
                file=sys.stderr,
            )
            return 1
        print("handbook assets are up to date.")
        return 0

    asset_dir.mkdir(parents=True, exist_ok=True)
    # Remove orphaned assets so a renamed/removed chapter can't linger.
    expected = set(files)
    for path in asset_dir.iterdir():
        if path.is_file() and path.name not in expected:
            path.unlink()
    for name, content in sorted(files.items()):
        (asset_dir / name).write_text(content, encoding="utf-8")
    print(f"wrote {len(files)} handbook asset(s) to {asset_dir.relative_to(root)}")
    print("  " + ", ".join(sorted(files)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
