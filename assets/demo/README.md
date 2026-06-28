# Launch demo

The README's hero GIF — the **"debug this" with an AI** story:

1. Hand Claude the **raw** JFR (`jfr print recording.jfr | claude -p "…"`) → *Prompt is too
   long · ~684K tokens (limit 1,000,000)* — a raw dump overflows the context window; the AI
   can't even read it.
2. Run the **same** recording through jvmlens first (`jvmlens analyze recording.jfr -r cpu |
   claude -p "…"`) → ~400 tokens of ranked signal, and Claude pins
   `Workload.expensiveHashLoop` (99%) and the concrete fix.

No `clear`, so the GIF loops on the payoff (the jvmlens-enabled answer is the last frame).

## Reproducible — and renders offline

```bash
./render.sh      # needs: vhs, ttyd, ffmpeg on PATH, and a JDK 17+ (with `jfr`)
```

It re-renders **deterministically with no live API call**, golden-file style: `claude` here is
a replay stub (`bin/claude`) that prints captured responses based on input size — the raw dump
(huge) → `claude-too-long.txt`, the jvmlens summary (small) → `claude-with-jvmlens.txt`.
`render.sh` puts `bin/` first on `$PATH` so the tape's `claude` (and the `jvmlens` launcher)
resolve to these.

### Contents

- `demo.tape` — the VHS script (edit timing/framing here).
- `recording.jfr` — a fixed seed recording so the GIF re-renders identically; regenerate with
  `./seed.sh` (runs `examples/Workload.java cpu` under JFR).
- `claude-with-jvmlens.txt` / `claude-too-long.txt` — the one-time **real** Claude captures
  replayed by the stub. To refresh: pipe the summary / raw dump to the real `claude -p` and
  re-save, then re-render.
- `bin/claude`, `bin/jvmlens` — the golden-replay stub + a thin CLI launcher (used only at
  render time; staged onto `$PATH` by `render.sh`).
- `render.sh` stages the CLI fat jar, runs `vhs`, and cleans up — only `recording.jfr`, the
  golden `.txt`, the scripts, and `jvmlens-demo.gif` are committed.

The `.tape` + seed + golden-replay approach is reusable for sibling projects (e.g. jhelm).
