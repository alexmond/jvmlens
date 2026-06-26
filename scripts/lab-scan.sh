#!/usr/bin/env bash
#
# lab-scan.sh — fail if homelab/lab-internal references (internal DNS, private IPs,
# lab registry/secret names) appear in tracked files, EXCEPT those already recorded
# in the baseline. New leaks fail; the existing debt is tracked, not blocking.
#
# Reusable across repos — the denylist (scripts/lab-deny.txt) is meant to be promoted
# to infra/lab/deny-patterns.txt and shared. See the Forgejo handoff ticket.
#
# Usage:
#   scripts/lab-scan.sh                  scan all tracked files (CI gate)
#   scripts/lab-scan.sh --staged         scan staged files only (pre-commit hook)
#   scripts/lab-scan.sh --update-baseline   record current hits as allowed (debt)
#   scripts/lab-scan.sh --install-hook   point git at .githooks for this clone
#
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
DIR="$ROOT/scripts"
DENY="$DIR/lab-deny.txt"
BASE="$DIR/lab-scan-baseline.txt"
MODE="${1:-all}"

if [ "$MODE" = "--install-hook" ]; then
	git -C "$ROOT" config core.hooksPath .githooks
	echo "✓ lab-scan: git hooks now run from .githooks (pre-commit guard active)"
	exit 0
fi

# Patterns with comments/blanks stripped, fed to grep -f.
PATFILE="$(mktemp)"
trap 'rm -f "$PATFILE"' EXIT
grep -vE '^[[:space:]]*(#|$)' "$DENY" >"$PATFILE" || true

# Emit "path<TAB>match" for every lab-pattern hit in the given newline-delimited
# file list, skipping the scanner's own files (which legitimately hold the patterns).
collect() {
	local f
	while IFS= read -r f; do
		[ -f "$ROOT/$f" ] || continue
		case "$f" in
			scripts/lab-deny.txt | scripts/lab-scan.sh | scripts/lab-scan-baseline.txt) continue ;;
		esac
		{ grep -hoiE -f "$PATFILE" -- "$ROOT/$f" 2>/dev/null || true; } | sort -u | while IFS= read -r m; do
			printf '%s\t%s\n' "$f" "$m"
		done
	done | LC_ALL=C sort -u
}

case "$MODE" in
	--update-baseline)
		git -C "$ROOT" ls-files | collect >"$BASE"
		echo "✓ lab-scan: baseline recorded $(wc -l <"$BASE" | tr -d ' ') allowed reference(s)"
		exit 0
		;;
	--staged) FILES="$(git -C "$ROOT" diff --cached --name-only --diff-filter=ACM)" ;;
	all | "") FILES="$(git -C "$ROOT" ls-files)" ;;
	*)
		echo "usage: lab-scan.sh [--staged|--update-baseline|--install-hook]" >&2
		exit 2
		;;
esac

HITS="$(printf '%s\n' "$FILES" | collect)"
ALLOWED=""
[ -f "$BASE" ] && ALLOWED="$(LC_ALL=C sort -u "$BASE")"
NEW="$(LC_ALL=C comm -23 <(printf '%s\n' "$HITS" | grep -v '^$' || true) <(printf '%s\n' "$ALLOWED" | grep -v '^$' || true) || true)"

if [ -n "$NEW" ]; then
	echo "❌ lab-scan: new lab-internal reference(s) in tracked files:" >&2
	printf '%s\n' "$NEW" | sed 's/^/  /' >&2
	echo "" >&2
	echo "Replace with a placeholder and move the real value to infra/lab/ (overlay or lab.env)." >&2
	echo "If this reference is genuinely safe, run: scripts/lab-scan.sh --update-baseline" >&2
	exit 1
fi
echo "✓ lab-scan: no new lab-internal references"
