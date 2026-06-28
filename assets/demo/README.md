# Demo artifacts — "jvmlens + your AI agent"

The real, committed inputs behind the docs page
**[jvmlens + your AI agent](https://www.alexmond.org/jvmlens/current/ai-agent.html)** (source:
`docs/modules/ROOT/pages/ai-agent.adoc`). Everything is genuine output, kept so the token
counts and transcript are checkable without re-running anything.

## Contents

- `recording.jfr` — a fixed seed recording (a CPU hot path planted by `examples/Workload.java`).
  Regenerate with `./seed.sh`. Its raw `jfr print` is 2.7 MB ≈ 684K tokens; `jvmlens analyze
  -r cpu` of it is ~1 KB (~250 tokens).
- `claude-too-long.txt` — the real Claude error when the raw dump is piped in (context overflow).
- `claude-with-jvmlens.txt` — the real Claude answer when the jvmlens summary is piped in (it
  pins `Workload.expensiveHashLoop` + the fix).

## Reproduce

```bash
# 1. (re)record the workload
./seed.sh        # or: java -XX:StartFlightRecording=...,settings=profile examples/Workload.java cpu 10

# 2. raw → your agent: overflows the context window
jfr print recording.jfr | claude -p "why is this slow and how do I fix it?"

# 3. jvmlens → your agent: ~250 tokens, and it solves it
java -jar ../../jvmlens-cli/target/jvmlens.jar analyze recording.jfr -r cpu \
  | claude -p "why is this slow and how do I fix it?"
```

> Earlier this was a VHS-scripted terminal GIF (jvmlens#93/#96); it was dropped in favour of
> this docs page — a terminal GIF is just an image of text, and a golden-replay stub would
> fake the one thing that should be real. Real, copyable artifacts are the honest proof.
