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

# 2. ensure the seed recording exists
[ -f "$here/recording.jfr" ] || "$here/seed.sh"

# 3. ensure the JDK `jfr` tool is on PATH
command -v jfr >/dev/null || export PATH="$(dirname "$(readlink -f "$(command -v java)")"):$PATH"

# 4. put the demo bin/ first on PATH so `jvmlens` (launcher) and `claude` (golden replay
#    stub — deterministic, offline) resolve here instead of the real binaries.
chmod +x "$here/bin/jvmlens" "$here/bin/claude"
export PATH="$here/bin:$PATH"

# 5. render the GIF
( cd "$here" && vhs demo.tape )

# 6. drop the staged jar (keep the repo lean; recording.jfr + golden txt stay committed)
rm -f "$here/jvmlens.jar"
echo "rendered: $here/jvmlens-demo.gif"
