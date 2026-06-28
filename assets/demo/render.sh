#!/usr/bin/env bash
# Render the jvmlens launch demo GIF from demo.tape — reproducible: one command.
# Needs: vhs + ttyd + ffmpeg on PATH, and a JDK 17+ whose bin (with `jfr`) is reachable.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"

# 1. stage the CLI jar next to the tape as `jvmlens.jar` (build if missing) — not committed
jar="$root/jvmlens-cli/target/jvmlens.jar"
[ -f "$jar" ] || ( cd "$root" && ./mvnw -q -pl jvmlens-cli -am package -DskipTests )
cp "$jar" "$here/jvmlens.jar"

# 2. ensure the seed recording exists, and stage the workload source for the code beat
[ -f "$here/recording.jfr" ] || "$here/seed.sh"
cp "$root/examples/Workload.java" "$here/Workload.java"   # so the GIF shows a clean path

# 3. ensure the JDK `jfr` tool is on PATH
command -v jfr >/dev/null || export PATH="$(dirname "$(readlink -f "$(command -v java)")"):$PATH"

# 4. render the GIF
( cd "$here" && vhs demo.tape )

# 5. drop the staged jar + source copy (keep the repo lean; recording.jfr stays committed)
rm -f "$here/jvmlens.jar" "$here/Workload.java"
echo "rendered: $here/jvmlens-demo.gif"
