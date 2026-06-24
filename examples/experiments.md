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

## Blind A/B — settled (2026-06-24)

Ran the turn-count / token A/B by handing the artifact to **fresh, isolated LLM
sessions** (no source, no answer key, one file each) and scoring whether they
named the planted method. 8 blind sessions total.

| Scenario | Input | Sessions | Named planted bug | Self-reported sufficiency |
|---|---|---|---|---|
| lock  | summary (351 tok)   | 3/3 | `criticalSection` + Object-monitor | all "direct" |
| lock  | raw events (1928 tok)| 3/3 | `criticalSection` + Object-monitor | all "direct" |
| cpu   | summary (322 tok)   | 1/1 | `expensiveHashLoop` (SHA hashing)  | "direct" |
| alloc | summary (322 tok)   | 1/1 | `handleUpload` + GC pressure/leak  | "direct" |

- **Accuracy: 8/8** named the exact planted method with high confidence.
- **`lock` is the fair head-to-head** (raw fits if filtered to signal events).
  Charitable raw = `jfr print --events jdk.JavaMonitorEnter,jdk.ThreadPark`
  (~1928 tok; the *unfiltered* short-lock raw is ~194K tok, still near overflow).
  Summary (351 tok) produced the **same diagnosis, same confidence, same
  "direct" sufficiency at 5.5× fewer input tokens** — and ~10% less end-to-end
  reasoning per session. Summary sessions dismissed the 8 noisy exec-samples as
  startup noise; raw sessions had to reconstruct the same conclusion from 16
  hand-off events.
- **Decision rule met on 3/3:** cpu (~3000× fewer tokens, raw unusable), alloc
  (raw unusable), lock (5.5× fewer at equal accuracy). The summarizer earns its
  place. Reproduce via the recordings + blind protocol in `target/exp/`.

## Decision rule

Summary wins (≥3× tokens or fewer turns) on ≥2 of 3 scenarios → the summarizer
earns its place. If raw is already good on all 3 → reframe as a thin
prompt-pack. (The compression result above already largely settles this.)

## Field findings — dogfooding real projects

Planted pathologies prove the summarizer on *known* answers. The harder test is
**real projects**: point jvmlens at another app you own (jhelm, unitrack,
venice-vr, …), see what the summary says, and feed every gap back here as a
GitHub issue. This is the loop that drives the roadmap.

**Protocol** (see `scripts/field-finding.sh`, which automates capture + filing):

```bash
# A) live attach to a running JVM (the workload, warm)
java -jar jvmlens.jar profile -d 20 <pid> -f prompt

# B) or capture a recording around a workload, then analyze
java -XX:StartFlightRecording=filename=run.jfr,dumponexit=true,settings=profile -jar app.jar ...
java -jar jvmlens.jar analyze run.jfr
```

For each run, file a `field-finding` issue with: the project + workload, a short
excerpt of the summary, and **the jvmlens gap it exposed** (what was wrong,
missing, or misleading). Perf bugs in the *target* go to that project; jvmlens
*requirements* come back here.

### Findings log

- **2026-06-23 — jhelm (`template` render of the grafana chart, cold CLI run).**
  A single fat-jar render produced only 71 exec samples, of which ~93 frame hits
  were `org.springframework.boot.loader.*` and just 6 touched `org.alexmond.jhelm.*`.
  Two jvmlens gaps, filed as issues:
  1. App-frame attribution skips only `java./jdk./sun./…`, so framework packages
     (Spring Boot loader, BouncyCastle) masquerade as "application code" and bury
     the user's own packages. Needs configurable package scoping.
     **Fixed (#1):** `Scope` broadens the default skip-list to common frameworks and
     adds `-a/--app-package` (include) + `-x/--exclude`. Re-run: default now leads with
     `HelmJavaApplication.main`; `-a org.alexmond` shows only jhelm/gotmpl4j code.
  2. Short cold CLI runs profile startup/classloading, not the workload; the
     summary should make sample-count adequacy visible and/or support steady-state
     capture.
     **Fixed (#2):** the markdown now emits a `⚠` caveat below a 200-sample
     threshold (the grafana run flags "Only 71 execution samples"), and `profile`
     gained `-w/--warmup` to skip startup before recording.
