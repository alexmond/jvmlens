#!/usr/bin/env bash
# Regenerate the demo recording from the bundled examples/Workload.java (cpu scenario:
# expensiveHashLoop dominates). A fixed recording.jfr is committed so the demo GIF
# re-renders identically — run this only to refresh it. Needs a JDK 17+ (single-file launch).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
java -XX:StartFlightRecording=duration=12s,filename="$here/recording.jfr",settings=profile \
     "$root/examples/Workload.java" cpu 10
echo "seeded: $here/recording.jfr"
