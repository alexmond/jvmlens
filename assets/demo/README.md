# Launch demo

The README's hero GIF — the **"debug this" flow**: ask `jvmlens analyze` about a slow run,
it names the hot method + lines (99% `Workload.expensiveHashLoop`, with source-line anchors)
and the cause, then you jump to the exact code. The contrast: a raw `jfr print` of the same
recording is ~2.7 MB (~680K tokens); the jvmlens summary is ~400.

## Reproducible — one command

```bash
./render.sh      # needs: vhs, ttyd, ffmpeg on PATH, and a JDK 17+ (with `jfr`)
```

- `demo.tape` — the VHS script (declarative; edit timing/framing here).
- `recording.jfr` — a fixed seed recording (so the GIF re-renders identically); regenerate
  with `./seed.sh` (runs `examples/Workload.java cpu` under JFR).
- `render.sh` stages the CLI jar + workload source, runs `vhs`, and cleans up — only
  `recording.jfr`, the scripts, and `jvmlens-demo.gif` are committed.

The `.tape` + seed approach is reusable for sibling projects (e.g. jhelm).
