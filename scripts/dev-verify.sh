#!/usr/bin/env bash
#
# Local pre-commit build: apply the spring-javaformat style, then run the full Maven
# verify across the reactor (compile, Checkstyle, PMD, tests, JaCoCo gate). This mirrors
# what CI runs, so a green run here means a green run on the PR.
#
# Usage:
#   scripts/dev-verify.sh                    # format + verify the whole reactor
#   scripts/dev-verify.sh -pl jvmlens-cli -am   # extra args pass through to `verify`
#
# Any arguments are forwarded to the `verify` invocation.

set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> spring-javaformat:apply"
./mvnw -q spring-javaformat:apply

echo "==> verify ${*:-(full reactor)}"
./mvnw -B verify "$@"

echo "==> OK — formatted and verified"
