# jvmlens launch demo — "debug this" with an AI, jvmlens makes it possible.
# Raw JFR overflows the model's context (AI helpless) → jvmlens analyze → Claude nails the
# method + fix. No `clear`, so the GIF loops on the win (the jvmlens answer is the last frame).
# Deterministic + offline: `claude` here is a golden-replay stub (see README). Render: ./render.sh
Output jvmlens-demo.gif

Require java

Set Shell bash
Set FontSize 22
Set Width 1360
Set Height 760
Set Padding 22
Set Theme "Dracula"
Set TypingSpeed 30ms

Hide
Type "clear" Enter
Show

# --- Without jvmlens: hand the raw JFR straight to the AI ---
Type `# "Why is this slow & how do I fix it?" — hand Claude the raw JFR:`
Enter
Sleep 700ms
Type `jfr print recording.jfr | claude -p "why is this slow & how do I fix it?"`
Enter
Sleep 3.2s

# --- With jvmlens: ~400 tokens of ranked signal → the AI nails it ---
Type `# Run it through jvmlens first — ~400 tokens instead of ~680,000:`
Enter
Sleep 1.3s
Type `jvmlens analyze recording.jfr -r cpu | claude -p "why is this slow & how do I fix it?"`
Enter
Sleep 4.5s
Sleep 3s
