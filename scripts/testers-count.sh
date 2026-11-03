#!/usr/bin/env bash
# Prints how many alpha testers the tracker in docs/alpha-testers.md lists, and
# how many have opted in, for the daily pass:
#
#   testers=3 opted_in=2
#
# Only the rows between the <!-- testers:start --> and <!-- testers:end -->
# markers count (the header and separator rows are skipped), so the field
# table and the post texts above the tracker can never inflate the number.
# --json prints {"testers":3,"opted_in":2}. Exit 2 if the markers are missing.
set -euo pipefail

doc="$(cd "$(dirname "$0")/.." && pwd)/docs/alpha-testers.md"
[[ -n "${TESTERS_DOC:-}" ]] && doc="$TESTERS_DOC"

if ! grep -q '<!-- testers:start -->' "$doc" || ! grep -q '<!-- testers:end -->' "$doc"; then
  echo "testers-count: tracker markers missing in $doc" >&2
  exit 2
fi

rows="$(awk '
  /<!-- testers:start -->/ { inside = 1; next }
  /<!-- testers:end -->/   { inside = 0 }
  inside && /^\|/ && !/^\|[ -]*\|/ && !/^\| *Date joined/ { print }
' "$doc")"

testers=0
opted=0
if [[ -n "$rows" ]]; then
  testers="$(printf '%s\n' "$rows" | grep -c .)"
  # Third column: Opted in (yes/no).
  opted="$(printf '%s\n' "$rows" | awk -F'|' 'tolower($4) ~ /^ *yes *$/' | grep -c . || true)"
fi

if [[ "${1:-}" == "--json" ]]; then
  printf '{"testers":%d,"opted_in":%d}\n' "$testers" "$opted"
else
  printf 'testers=%d opted_in=%d\n' "$testers" "$opted"
fi
