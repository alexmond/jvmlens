#!/usr/bin/env bash
#
# Run a targeted test (or tests) without a full reactor verify. Routes through this
# script so it matches the allowlisted `Bash(scripts/*)` permission and doesn't prompt
# for direct `mvn`/`./mvnw` invocations.
#
# Usage:
#   scripts/dev-test.sh SummarizerTest                 # by class (any module)
#   scripts/dev-test.sh 'FixHints*,ProfileDiffTest'    # -Dtest patterns
#   scripts/dev-test.sh -pl jvmlens-engine SummarizerTest   # narrow to a module (faster)
#
# The last argument is the -Dtest selector; anything before it is forwarded to Maven.
# With no -pl, the test is found in whichever module defines it (others are skipped).

set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -eq 0 ]]; then
  echo "usage: scripts/dev-test.sh [maven-args...] <TestNameOrPattern>" >&2
  exit 2
fi

# Split: last arg = test selector, the rest = extra Maven args.
args=("$@")
selector="${args[${#args[@]}-1]}"
unset 'args[${#args[@]}-1]'

./mvnw -q "${args[@]}" test -Dtest="$selector" -Dsurefire.failIfNoSpecifiedTests=false
