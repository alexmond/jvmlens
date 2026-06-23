#!/usr/bin/env bash
# Dogfood jvmlens against a real project, then file the finding back here as a
# GitHub issue. Run from any project; it captures a profile and opens a
# pre-filled `field-finding` issue on the jvmlens repo.
#
# Usage:
#   field-finding.sh --pid <pid> [-d <seconds>] --project "<name + workload>"
#   field-finding.sh --jfr <file.jfr>           --project "<name + workload>"
#
# Requires: a built jvmlens jar (JVMLENS_JAR, default ./target/jvmlens.jar or
# ~/IdeaProjects/jvmlens/target/jvmlens.jar) and `gh` authenticated.
set -euo pipefail

JVMLENS_JAR="${JVMLENS_JAR:-}"
if [[ -z "$JVMLENS_JAR" ]]; then
  for c in ./target/jvmlens.jar "$HOME/IdeaProjects/jvmlens/target/jvmlens.jar"; do
    [[ -f "$c" ]] && JVMLENS_JAR="$c" && break
  done
fi
REPO="${JVMLENS_REPO:-alexmond/jvmlens}"
DURATION=20 PID="" JFR="" PROJECT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pid) PID="$2"; shift 2;;
    -d|--duration) DURATION="$2"; shift 2;;
    --jfr) JFR="$2"; shift 2;;
    --project) PROJECT="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

[[ -f "$JVMLENS_JAR" ]] || { echo "jvmlens jar not found; set JVMLENS_JAR" >&2; exit 2; }
[[ -n "$PROJECT" ]] || { echo "--project is required" >&2; exit 2; }

if [[ -n "$PID" ]]; then
  MODE="profile <pid> (live attach)"
  SUMMARY="$(java -jar "$JVMLENS_JAR" profile -d "$DURATION" "$PID")"
elif [[ -n "$JFR" ]]; then
  MODE="analyze <file.jfr> (offline recording)"
  SUMMARY="$(java -jar "$JVMLENS_JAR" analyze "$JFR")"
else
  echo "one of --pid or --jfr is required" >&2; exit 2
fi

echo "===== jvmlens summary ====="; echo "$SUMMARY"; echo "==========================="
echo
read -r -p "Describe the jvmlens gap this exposed: " GAP

gh label create field-finding --repo "$REPO" --color BFD4F2 \
  --description "Requirement surfaced by profiling a real project" 2>/dev/null || true

BODY="$(printf '## Target project & workload\n%s\n\n## Capture mode\n%s\n\n## Summary excerpt\n```\n%s\n```\n\n## The jvmlens gap\n%s\n' \
  "$PROJECT" "$MODE" "$SUMMARY" "$GAP")"

gh issue create --repo "$REPO" --label field-finding \
  --title "[field-finding] $PROJECT" --body "$BODY"
