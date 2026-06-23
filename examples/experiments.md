# Experiments — summarizer quality

How to validate that jvmlens's summary is meaningfully better than dumping raw
JFR into an LLM. Carried from the incubator; the summarizer now exists, so
INPUT B is produced by `jvmlens analyze` rather than hand-written.

## The question (the whole-idea gate)

Is a short, structured, LLM-targeted summary of a JFR profile meaningfully
better than raw `jfr print` into an LLM? **Success metric:** the summary must
beat raw on at least one of — ≥3× fewer tokens at equal diagnosis accuracy, OR
correct root cause in fewer turns.

## Planted pathologies (ground truth = the answer key)

`Workload.java` plants one known bug per scenario:

| Scenario | Planted bug (ground truth) | Signal events |
|---|---|---|
| `cpu`   | `expensiveHashLoop()` re-hashes 2000× — CPU hot path | ExecutionSample |
| `alloc` | `handleUpload()` retains 64KB payloads in static `LEAK_CACHE` | ObjectAllocationSample, OldObjectSample, GCPhasePause |
| `lock`  | 16 threads contend `SHARED_LOCK` in `criticalSection()` | JavaMonitorEnter |

## Run

```bash
# record (single-file source launch, JDK 17+)
java -XX:StartFlightRecording=duration=25s,filename=recording-cpu.jfr,settings=profile Workload.java cpu 20

# INPUT B: the jvmlens summary
java -jar ../target/jvmlens.jar analyze recording-cpu.jfr

# INPUT A (the raw baseline, for comparison):
jfr print recording-cpu.jfr > raw-cpu.txt   # note: often too large to paste
```

## Results so far (2026-06-22)

- **Compression:** a 20s `settings=profile` recording → raw `jfr print` is
  ~380K (cpu) / ~460K (alloc) tokens — both **overflow a 200K context window**;
  the summary is ~400 tokens. For cpu/alloc, raw isn't worse, it's *unusable*.
- **Correctness:** the v0.2 summarizer names the planted bug on all three
  scenarios (cpu → `expensiveHashLoop`, alloc → `handleUpload` + GC pressure,
  lock → `criticalSection`).
- **Still open:** the blind *turn-count* A/B (summary vs raw into fresh LLM
  sessions, scoring turns) — owner-run. `lock` (~5K tokens raw) is the clean
  head-to-head since raw fits there.

## Decision rule

Summary wins (≥3× tokens or fewer turns) on ≥2 of 3 scenarios → the summarizer
earns its place. If raw is already good on all 3 → reframe as a thin
prompt-pack. (The compression result above already largely settles this.)
