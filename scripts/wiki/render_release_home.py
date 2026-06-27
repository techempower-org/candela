#!/usr/bin/env python3
"""Render the release-current regions of the wiki Home.md from CHANGELOG.md.

Owned by .github/workflows/release-docs-sync.yml, which direct-pushes the wiki
on every release tag (no PR, no PAT — the .wiki repo is unprotected). This is
the ONLY writer of wiki Home.md; the companion wiki-sync.yml deliberately never
touches Home.md, so the two workflows never collide.

Four regions are regenerated — three from the tag's CHANGELOG.md section, plus
the intro counts derived live from the repo's own source of truth:

  1. The current-version line          `_Current version: vX.Y.Z_`
  2. The "Latest release" line         `📦 **Latest release:** [vX — Title](url) — tagline`
  3. The "What's new" section          fenced by AUTO-GENERATED markers and
                                       rebuilt from the release's bullets.
  4. The intro/Pages counts            "twenty-five fiction backends" and
                                       "thirty-eight modules" — recomputed from
                                       settings.gradle.kts + the source-* tree so
                                       they can never silently re-stale (#1146).

Region 4 self-heals: it finds the spelled-out number-word in front of "fiction
backends" / "backends" (the Pages link) / "modules" and rewrites it to match the
live count, preserving the original capitalization. If the phrasing ever changes
so a count can't be located it warns (so the workflow log flags it) but does NOT
fail the release — the intro is hand-authored prose, not a release-blocking region.

Everything else in Home.md ("Recent highlights", Quick links) is hand-authored
and left untouched — regions 1-3 fail loudly if they cannot be rewritten.

Usage:  REPO=owner/name TAG=v1.1.3 CHANGELOG=/path/to/CHANGELOG.md \\
            render_release_home.py /path/to/Home.md
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

# Fence markers for the auto-owned "What's new" section. Stable across runs so
# the section is replaced in place rather than appended.
WN_BEGIN = "<!-- AUTO-GENERATED: release-whats-new BEGIN (release-docs-sync.yml) -->"
WN_END = "<!-- AUTO-GENERATED: release-whats-new END -->"


def parse_changelog_section(changelog: str, version: str) -> tuple[str, list[str]]:
    """Return (tagline, bullets) for `## [version] -- date` in the changelog.

    `tagline` is the bold lead sentence + its summary (the line after the
    heading, e.g. "**Mark the page.** In-reader text highlighting.").
    `bullets` are the top-level `- ` list items across all `###` subsections,
    in document order, with their multi-line continuations folded to one line.
    """
    # Match the heading for this exact version, capture body up to the next
    # `## [` heading (or EOF). Version may carry a leading 'v'.
    v = version.lstrip("v")
    heading_re = re.compile(
        r"^##\s*\[" + re.escape(v) + r"\][^\n]*\n(?P<body>.*?)(?=^##\s*\[|\Z)",
        re.M | re.S,
    )
    m = heading_re.search(changelog)
    if not m:
        raise SystemExit(f"render_release_home: no CHANGELOG section for [{v}]")
    body = m.group("body").strip("\n")

    # Tagline: the first non-empty line of the body, before any `###` subsection.
    tagline = ""
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("#"):
            break
        tagline = stripped
        break

    # Bullets: every top-level `- ` item (deeper indented continuations are
    # folded onto the preceding bullet). Bullets under a `###` subsection whose
    # heading is flagged "(internal)" are dropped — the flag lives on the
    # SUBSECTION HEADING (e.g. "### Fixed (internal)"), not on the bullet text,
    # so we track the active subsection and skip accordingly.
    bullets: list[str] = []
    cur: str | None = None
    skip_subsection = False

    def flush() -> None:
        nonlocal cur
        if cur is not None and not skip_subsection:
            bullets.append(cur)
        cur = None

    for line in body.splitlines():
        if line.startswith("#"):                       # a `###` subsection heading
            flush()
            skip_subsection = "(internal)" in line.lower()
        elif re.match(r"^- ", line):
            flush()
            cur = line[2:].strip()
        elif re.match(r"^\s+\S", line) and cur is not None:
            cur += " " + line.strip()
        elif line.strip() == "":
            flush()
    flush()

    # Also drop any individual bullet that self-flags internal, belt-and-braces.
    bullets = [b for b in bullets if "(internal)" not in b.lower()]
    return tagline, bullets


def render_whats_new(version: str, tagline: str, bullets: list[str]) -> str:
    """Build the fenced 'What's new' section body."""
    lines = [WN_BEGIN, f"## What's new in {version}", ""]
    if tagline:
        lines.append(tagline)
        lines.append("")
    if bullets:
        lines.extend(f"- {b}" for b in bullets)
    else:
        lines.append("_See the [release notes](RELEASE_URL) for details._")
    lines.append(WN_END)
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Intro count derivation (#1146). The wiki Home intro carries spelled-out
# counts ("twenty-five fiction backends", "thirty-eight modules") that were
# owned by no sync workflow and silently re-staled on every release that added
# a source or module. We now recompute them from the repo's source of truth.
# ---------------------------------------------------------------------------

_ONES_WORDS = [
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
    "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
    "sixteen", "seventeen", "eighteen", "nineteen",
]
_TENS_WORDS = [
    "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
    "eighty", "ninety",
]
_ONES_INDEX = {w: i for i, w in enumerate(_ONES_WORDS)}
_TENS_INDEX = {w: i * 10 for i, w in enumerate(_TENS_WORDS) if w}


def num_to_words(n: int) -> str:
    """Spell a 0-99 integer ("twenty-five"). Out of range -> digits."""
    if not 0 <= n <= 99:
        return str(n)
    if n < 20:
        return _ONES_WORDS[n]
    tens, ones = divmod(n, 10)
    return _TENS_WORDS[tens] + (f"-{_ONES_WORDS[ones]}" if ones else "")


def parse_number_word(word: str) -> int | None:
    """Inverse of [num_to_words] for 0-99; None if `word` isn't a number-word.

    Used as a guard so the count substitution only ever touches spans whose
    leading token is genuinely a spelled number — "core modules" or "Gradle
    modules" parse to None and are left alone.
    """
    w = word.strip().lower()
    if w in _ONES_INDEX:
        return _ONES_INDEX[w]
    if w in _TENS_INDEX:
        return _TENS_INDEX[w]
    if "-" in w:
        tens, _, ones = w.partition("-")
        if tens in _TENS_INDEX and ones in _ONES_INDEX and 1 <= _ONES_INDEX[ones] <= 9:
            return _TENS_INDEX[tens] + _ONES_INDEX[ones]
    return None


def count_modules(settings_text: str) -> int:
    """Count live `include(":...")` modules — mirrors sync_wiki.parse_modules."""
    include_re = re.compile(r"""include\(\s*['"](:[^'"]+)['"]\s*\)""")
    mods: set[str] = set()
    for line in settings_text.splitlines():
        stripped = line.strip()
        if stripped.startswith(("//", "/*", "*")):
            continue
        m = include_re.search(stripped)
        if m:
            mods.add(m.group(1))
    return len(mods)


def count_fiction_backends(repo_root: Path) -> int:
    """Count `source-*` modules whose main sources implement `FictionSource`."""
    impl_re = re.compile(r":\s*FictionSource\b")
    count = 0
    for module_dir in sorted(repo_root.glob("source-*")):
        main_dir = module_dir / "src" / "main"
        if not main_dir.is_dir():
            continue
        for kt in main_dir.rglob("*.kt"):
            try:
                if impl_re.search(kt.read_text(encoding="utf-8", errors="ignore")):
                    count += 1
                    break
            except OSError:
                continue
    return count


# Three count spans, each (prefix)(number-word)(suffix). The number-word group
# is validated against parse_number_word before substitution, so non-numeric
# matches (e.g. "core modules") are left untouched.
_FICTION_RE = re.compile(
    r"(?P<num>[A-Za-z]+(?:-[A-Za-z]+)?)(?P<post>\s+fiction\s+backends)"
)
_ALL_BACKENDS_RE = re.compile(
    r"(?P<pre>\bAll\s+)(?P<num>[A-Za-z]+(?:-[A-Za-z]+)?)(?P<post>\s+backends)"
)
_MODULES_RE = re.compile(
    r"(?P<num>[A-Za-z]+(?:-[A-Za-z]+)?)(?P<post>\s+modules\b)"
)


def _match_leading_case(template: str, new: str) -> str:
    """Apply the leading-cap of `template` to `new` (Thirty-four vs twenty-five)."""
    if template[:1].isupper():
        return new[:1].upper() + new[1:]
    return new


def update_intro_counts(
    s: str, n_sources: int, n_modules: int
) -> tuple[str, list[str]]:
    """Rewrite the spelled-out intro counts to match the live repo counts.

    Scoped to the hand-authored upper region (intro + Pages list) — never the
    generated What's-new section or the hand-curated highlights below it.
    Returns the new text plus a list of human-readable warnings for any count
    span that couldn't be located (non-fatal; surfaced in the workflow log).
    """
    split_idx = len(s)
    for marker in (WN_BEGIN, "\n## What's new", "\n## Recent"):
        idx = s.find(marker)
        if idx != -1:
            split_idx = min(split_idx, idx)
    head, tail = s[:split_idx], s[split_idx:]
    notes: list[str] = []

    src_word = num_to_words(n_sources)
    mod_word = num_to_words(n_modules)

    def make_repl(target_word: str, counter: list[int]):
        def _repl(m: "re.Match[str]") -> str:
            if parse_number_word(m.group("num")) is None:
                return m.group(0)  # not a number-word — leave the prose alone
            counter[0] += 1
            pre = m.groupdict().get("pre") or ""
            return pre + _match_leading_case(m.group("num"), target_word) + m.group("post")
        return _repl

    # Count only REAL substitutions — a span whose token actually parsed as a
    # number — not raw regex hits. "many fiction backends" matches the pattern
    # but is not a count, so it must still be reported as missing (and left
    # untouched), not silently treated as "found".
    n_fiction = [0]
    n_all = [0]
    n_modules_hits = [0]
    head = _FICTION_RE.sub(make_repl(src_word, n_fiction), head)
    head = _ALL_BACKENDS_RE.sub(make_repl(src_word, n_all), head)
    head = _MODULES_RE.sub(make_repl(mod_word, n_modules_hits), head)

    if n_fiction[0] == 0:
        notes.append("no '<N> fiction backends' count found in Home intro")
    if n_modules_hits[0] == 0:
        notes.append("no '<N> modules' count found in Home intro")
    # The Pages "All <N> backends" link is optional — only warn if neither
    # backend phrasing was found at all (i.e. the whole backends count is gone).
    if n_fiction[0] == 0 and n_all[0] == 0:
        notes.append("no 'All <N> backends' Pages link count found")

    return head + tail, notes


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: render_release_home.py <Home.md>")
    home_path = Path(sys.argv[1])
    tag = os.environ["TAG"]                      # e.g. v1.1.3
    repo = os.environ["REPO"]                    # owner/name
    changelog_path = Path(os.environ["CHANGELOG"])

    release_url = f"https://github.com/{repo}/releases/tag/{tag}"
    changelog = changelog_path.read_text(encoding="utf-8")
    tagline, bullets = parse_changelog_section(changelog, tag)

    # Title for the "Latest release" line: the bold lead of the tagline, e.g.
    # "**Mark the page.** ..." -> "Mark the page". Fall back to the tag.
    title_m = re.match(r"\*\*(?P<t>[^*]+?)\.?\*\*", tagline)
    release_title = title_m.group("t").strip() if title_m else tag
    # The descriptive remainder after the bold lead (for the Latest-release line).
    remainder = tagline[title_m.end():].strip() if title_m else tagline

    s = home_path.read_text(encoding="utf-8")
    orig = s

    # 1. Current-version line.
    ver_re = re.compile(r"^_Current version: v\d+\.\d+\.\d+_\s*$", re.M)
    ver_line = f"_Current version: {tag}_"
    if ver_re.search(s):
        s = ver_re.sub(ver_line, s, count=1)
    else:
        # Insert just after the first H1.
        s = re.sub(r"(\A#[^\n]+\n)", lambda m: m.group(1) + "\n" + ver_line + "\n",
                   s, count=1)

    # 2. Latest-release line.
    latest_re = re.compile(r"^📦 \*\*Latest release:\*\*.*$", re.M)
    latest_line = (
        f"📦 **Latest release:** [{tag} — {release_title}]({release_url})"
        + (f" — {remainder}" if remainder else "")
    )
    if latest_re.search(s):
        s = latest_re.sub(lambda _m: latest_line, s, count=1)
    else:
        raise SystemExit("render_release_home: no '📦 **Latest release:**' line in Home.md")

    # 3. "What's new" section (fenced + idempotent).
    whats_new = render_whats_new(tag, tagline, bullets).replace("RELEASE_URL", release_url)
    fenced_re = re.compile(re.escape(WN_BEGIN) + r".*?" + re.escape(WN_END), re.S)
    if fenced_re.search(s):
        s = fenced_re.sub(lambda _m: whats_new, s, count=1)
    else:
        # First adoption: replace the legacy, unfenced "## What's new in vX"
        # block (up to the next `## ` heading) with the fenced version.
        legacy_re = re.compile(r"^## What's new in v\d+\.\d+\.\d+.*?(?=^## )", re.M | re.S)
        if legacy_re.search(s):
            s = legacy_re.sub(whats_new + "\n\n", s, count=1)
        else:
            raise SystemExit("render_release_home: no '## What's new' section to take over")

    # 4. Intro counts (#1146) — derive live from the repo's source of truth so
    # the "twenty-five fiction backends" / "thirty-eight modules" intro can't
    # silently re-stale. Non-fatal: a derivation failure (missing settings file,
    # script run outside the repo) must never block a release.
    repo_root = Path(os.environ.get("REPO_ROOT") or Path(__file__).resolve().parents[2])
    try:
        settings_path = repo_root / "settings.gradle.kts"
        n_modules = count_modules(settings_path.read_text(encoding="utf-8"))
        n_sources = count_fiction_backends(repo_root)
        if n_modules and n_sources:
            s, notes = update_intro_counts(s, n_sources, n_modules)
            for note in notes:
                print(f"::warning::render_release_home: {note}")
            print(f"render_release_home: intro counts -> {n_sources} fiction "
                  f"backends, {n_modules} modules.")
        else:
            print("::warning::render_release_home: could not derive intro counts "
                  f"(modules={n_modules}, sources={n_sources}) — leaving intro untouched.")
    except OSError as e:
        print(f"::warning::render_release_home: intro-count derivation skipped: {e}")

    if s != orig:
        home_path.write_text(s, encoding="utf-8")
        print(f"render_release_home: Home.md updated to {tag} "
              f"({len(bullets)} bullet(s)).")
    else:
        print(f"render_release_home: Home.md already current for {tag}.")


if __name__ == "__main__":
    main()
