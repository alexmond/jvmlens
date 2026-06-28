# jvmlens launch demo — the "debug this" flow: profile → jvmlens names the hot
# method + line → jump to the real code. Reproducible render: ./render.sh
# (needs vhs + ttyd + ffmpeg + a JDK 17+ with `jfr` on PATH).
Output jvmlens-demo.gif

Require java

Set Shell bash
Set FontSize 22
Set Width 1320
Set Height 680
Set Padding 22
Set Theme "Dracula"
Set TypingSpeed 32ms

Hide
Type "clear" Enter
Show

# --- Beat 1: "debug this" → ask jvmlens ---
Type `# A service is slow. "Debug this" — profile it, then ask jvmlens:`
Enter
Sleep 800ms
Type "java -jar jvmlens.jar analyze recording.jfr -r cpu"
Enter
Sleep 5s
Sleep 1.5s
Type "clear"
Enter

# --- Beat 2: jump to the exact code it named ---
Type "# jvmlens pinned Workload.expensiveHashLoop (99%). Pull up those lines:"
Enter
Sleep 800ms
Type "sed -n '41,49p' Workload.java"
Enter
Sleep 3.5s
Type "# 2000x SHA-256 re-hash + per-byte toHexString in the inner loop — the bug."
Enter
Sleep 2.2s
Type "# Raw 'jfr print' was 2.7 MB (~680K tokens); jvmlens: ~400 — one read to the fix."
Enter
Sleep 3s
