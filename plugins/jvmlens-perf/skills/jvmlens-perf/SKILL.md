---
name: jvmlens-perf
description: Use jvmlens to drive the dev-time optimize→measure loop on the CURRENT JVM project — find where CPU/allocation goes, fix the top lever, prove the win, and gate regressions. jvmlens turns a JFR recording into a ~400-token, source-attributed (down to `file:line`), LLM-ready hot-path/allocation summary (vs a ~1M-token raw `jfr print`), so a coding agent can reason over it. Pairs with JMH: JMH gives the number (throughput, bytes/op), jvmlens gives the where/why. Workflow — capture a JFR (JMH `-prof jfr` or the inline `JvmlensProfiler`, **`bench --main` for a non-JMH app/`main`**, a standalone `-XX:StartFlightRecording`, or `profile <pid>`) → `analyze <jfr> -a <app.pkg>` for ranked hot paths + allocation sites (add `--hints` for fix directions, `--max-tokens` to budget the output) → fix the top lever → `analyze <after> -b <before>` to diff what changed on **absolute** weight (NEW/GONE, Δ) → optional `--assert` CI perf-gate. Use when the user says "make X faster", "optimize this", "where is the time/memory going", "profile this benchmark", "profile this app/main (no JMH)", "why is this slow", "compare before/after a fix", "find the hot path / allocation", or "add a perf gate". For long-running production monitoring (drop-in agent + `trend` over days) use the sibling **jvmlens-monitor** skill instead. jvmlens lives at github.com/alexmond/jvmlens; it runs locally, never calls an LLM, never ships recordings anywhere.
---

# Optimize the current project with jvmlens

The dev-time **optimize→measure loop**: profile → find the top lever → fix → prove it → guard
it. jvmlens reads JFR (the JDK already produces it) and emits a few hundred tokens of ranked,
application-attributed signal you can act on. This skill is the optimization counterpart to
**jvmlens-monitor** (which owns the long-running monitor + `trend`). For a worked end-to-end
example of this loop on a real engine (profile → top lever → fix → prove byte-identical →
guard), see the **floatString case study** at
https://www.alexmond.org/jvmlens/current/case-study-floatstring.html.

> Substitute the current project's app package(s) for `com.example.app` throughout.
> jvmlens runs locally only — no egress.

## 0. Get the jvmlens jar (once)

**On Maven Central** (`org.alexmond:jvmlens-cli` — the `jvmlens-cli` artifact *is* the
executable fat jar; `jvmlens-jmh` and `jvmlens-agent` are siblings). Pull a pinned release, or
grab the rolling pre-release, or build it (Java 17+). Skip if you already have a jar.

```bash
# (a) Maven Central — pinned release (set VER to the latest release)
VER=0.2.2
curl -fsSL "https://repo1.maven.org/maven2/org/alexmond/jvmlens-cli/$VER/jvmlens-cli-$VER.jar" -o /tmp/jvmlens.jar && JVMLENS=/tmp/jvmlens.jar
# (b) or the rolling `latest` GitHub pre-release (CLI fat jar)
gh release download latest -R alexmond/jvmlens -p 'jvmlens.jar' -O /tmp/jvmlens.jar && JVMLENS=/tmp/jvmlens.jar
# (c) or build from source (Maven reactor — jars land in per-module target/ dirs)
git clone https://github.com/alexmond/jvmlens /tmp/jvmlens-src && ( cd /tmp/jvmlens-src && ./mvnw -q clean package -DskipTests )
JVMLENS=/tmp/jvmlens-src/jvmlens-cli/target/jvmlens.jar
```

## 1. Capture a JFR of the hot workload

Pick the capture that matches what you're optimizing:

- **A JMH benchmark (the usual case for library perf):** JMH has a built-in JFR profiler.
  ```bash
  java -jar target/benchmarks.jar "MyBenchmark.hotMethod" -p n=1000 -f 1 -wi 3 -i 6 -w 1 -r 2 \
       -prof "jfr:dir=/tmp/jfr-before;configName=profile"
  ```
  It writes `profile.jfr` into a per-benchmark subdir under `/tmp/jfr-before/`. Then `analyze`
  (step 2). Use this when you want the `.jfr` kept for a `--baseline` diff.
- **Smoothest one-shot for JMH — inline summary, no separate `analyze`:** drop the
  dependency-light `jvmlens-jmh.jar` (engine + profiler; `gh release download latest -R
  alexmond/jvmlens -p 'jvmlens-jmh.jar'`, or build → `jvmlens-jmh/target/jvmlens-jmh.jar`) on the benchmark classpath and
  run jvmlens's JMH profiler. It's invoked by **fully-qualified name** (JMH has no profiler
  ServiceLoader) and its scope option is **`appPackage=`** (singular, `+`-separated — *not* the
  CLI's `-a`/comma; the short name or `appPackages=` fail silently):
  ```bash
  java -cp "target/benchmarks.jar:/path/to/jvmlens-jmh.jar" \
       org.openjdk.jmh.Main "MyBenchmark.hotMethod" \
       -prof "org.alexmond.jvmlens.jmh.JvmlensProfiler:appPackage=com.example.app"
  ```
  Prints the ranked summary at end-of-trial. An unknown option key **hard-errors** with a
  did-you-mean. **Add JMH's own `-prof gc`** alongside it — the profiler then prints the **exact
  measured bytes/op** next to the sampled allocation sites, so the sampled per-site shares are
  anchored to a ground-truth number.
  For a before/after **diff entirely inside JMH**, keep this run's recording and diff the next
  against it — no separate `analyze`:
  ```bash
  # run 1: keep the fork's recording (+ its measured bytes/op, with -prof gc)
  -prof gc -prof "org.alexmond.jvmlens.jmh.JvmlensProfiler:appPackage=com.example.app;keep=/tmp/before.jfr"
  # run 2 (after the fix): print the diff + a MEASURED A/B verdict vs run 1
  -prof gc -prof "org.alexmond.jvmlens.jmh.JvmlensProfiler:appPackage=com.example.app;baseline=/tmp/before.jfr"
  ```
  With `-prof gc` + `baseline=`, run 2 prints a **measured A/B verdict** that gates on JMH's
  exact bytes/op with a significance call (SIGNIFICANT only when |Δ| exceeds the combined error
  band *and* the confidence intervals don't overlap), and warns on a single fork (use `-f 2`+ so
  cross-fork variance is real). So a sampled per-method swing can't be mistaken for the real win.
  It also prints a **dispersion verdict**: a genuine structural allocation removal collapses the
  cross-fork **variance band** (the removed allocation *was* the noise) — `±17,200 → ±35 /op,
  variance collapsed ~500×` is a strong real-win signal the mean alone can't give; and when
  bytes/op goes **near-deterministic** it emits a **STOP signal** (diminishing returns — pivot off
  allocation, the residual is intrinsic floor). A **measured throughput A/B** verdict sits beside
  it (the CPU analog): if a sampled hot-path share moved a lot but JMH's wall-clock throughput is
  *flat*, it says so — **a CPU-share shift is not a speedup** (don't ship a "57% faster" claim the
  ops/s doesn't support). Note the inline `baseline=` matches **one** benchmark method — record a
  *per-benchmark* baseline; a baseline JFR for a different method is detected and the verdict
  skipped with a warning, not silently wrong.
- **A non-JMH app / library (no benchmark module) — `bench`:** don't hand-roll a warm-loop
  driver. Point `bench` at any `main(String[])`; it runs a warmup→timed loop, captures the JFR
  over **only** the timed phase, and summarizes:
  ```bash
  java -jar "$JVMLENS" bench --main com.example.app.RenderDriver --cp target/classes:$(cat cp.txt) \
       -w 20 -i 200 -a com.example.app --jfr /tmp/before.jfr -- <driver args>
  ```
  `--cp` loads the workload (it needn't be on jvmlens's classpath); `-w/--warmup` + `-i/--iters`
  are iteration counts; `--jfr` keeps the recording for a `--baseline` diff (else a temp file).
  This is the no-JMH path most consumer apps want — write a tiny driver `main` that exercises the
  hot path once, and `bench` is the loop.
- **A standalone run / `main` (manual):** `java -XX:StartFlightRecording=filename=/tmp/run.jfr,settings=profile,duration=30s -jar app.jar` — prefer `bench` over this when you can call a `main`.
- **A running JVM:** `java -jar "$JVMLENS" profile <pid>` (live attach + timed capture; `-w` skips startup).

`settings=profile` (vs `default`) enables allocation + lock events — you want those for perf work.

## 2. Analyze — ranked hot paths + allocation, scoped to your code

```bash
jfr=$(find /tmp/jfr-before -name '*.jfr' | head -1)
java -jar "$JVMLENS" analyze "$jfr" -a com.example.app             # markdown
java -jar "$JVMLENS" analyze "$jfr" -a com.example.app -f prompt   # LLM-wrapped
java -jar "$JVMLENS" analyze "$jfr" -a com.example.app --hints     # + hedged fix directions
```

`-a` (repeatable) scopes "application code" to your package(s) so the hot paths are *your*
methods, not JDK leaves. `-x` excludes a package prefix (from hot paths, allocation sites, **and**
the allocated-types rollup — e.g. `-x org.h2` folds an embedded DB's types into one line). The
output ranks hot paths (by sample share), self-time leaves, allocation sites + types, lock
contention, and a hedged cause. **Act on the top 1–2 lines.**

Useful flags:
- `--hints` — append a hedged `[possible]` fix-direction section, tagged **structural**
  (mechanical/safe, e.g. iterator+lambda alloc, presize, reflect, **per-call regex compile →
  hoist the `Pattern` to `static final`**) vs **inherent** (parity-sensitive, e.g. number→string
  formatting) — pull the structural lever first.
- `--max-tokens <n>` (or `--top-k <n>`) — budget the output: shrinks rows until it fits ~`<n>`
  tokens. Handy when feeding several summaries to a model.
- `--skip-warmup <ms>` — drop the first `<ms>` of the recording so hot paths reflect steady
  state, not JIT/classload churn (per-file cutoff; the steady-state fix for fresh-JVM captures).
- `--source <roots>` — echo the **source-line text** at each `file:line` anchor inline
  (`floatString:129 ⟶ mantissa.substring(0, dot) + …`), so you see the offending line without
  opening the file. Off by default; comma/path-sep roots (e.g. `src/main/java`); resolves
  locally, degrades silently if a file isn't found. Great for feeding an agent.

**Reading the output:**
- Each hot-path row's teaser lists the **top leaves with counts** (`Bar.baz:88 30/168`) — where
  time *actually* goes — and the **source line** (`:88`); alloc sites show their call-site line.
  Flags `⚠ diffuse` when no single leaf holds >20% of the path (don't chase one frame).
- A `⚠ Only N allocation samples` caveat means per-site **byte shares** are noisy on a short
  trial — the **total** bytes are still reliable.
- An alloc row may carry `⚠ <type> may be scalar-replaced (escape analysis)` — JFR still samples
  a non-escaping box/lambda that C2 *eliminates* at steady state, so it can be a **false lever**.
  Confirm it's real with `-prof gc` before optimizing it.

## 3. The optimize→measure loop

1. Fix the **top lever** the summary named (a hot path or allocation site — not a guess).
2. Re-capture into a *separate* dir (`-prof "jfr:dir=/tmp/jfr-after;..."`).
3. **Diff** — name exactly what changed instead of eyeballing two summaries:
   ```bash
   before=$(find /tmp/jfr-before -name '*.jfr' | head -1)
   after=$(find /tmp/jfr-after  -name '*.jfr' | head -1)
   java -jar "$JVMLENS" analyze "$after" -b "$before" -a com.example.app
   ```
   The diff shows totals Δ, then hot paths / allocation sites with `50%→8% (▼42pp)` + `NEW` /
   `GONE`, ranked by change size. Iterate until the summary points somewhere not worth chasing.
   - **Extract-method refactors:** the diff also prints an **"Allocation by type (rollup)"** block
     that *sums extracted helpers* (`GoFmt.* — 7.6 GB → 5.8 GB [3 methods]`), so a win split across
     a new helper row reads as one net change instead of a misleading per-method `−52%` next to a
     `NEW` row. It also caveats a sampled allocation Δ in the 5–15% noise band.
   - **Don't misread a ▲ CPU row as a regression:** a hot-path row whose absolute samples rose
     while the **total** exec samples *fell*, stayed *~flat*, or **outpaced a modestly-rising total**
     (its share climbed) is hedged `(possible sampling redistribution …)`; under a fixed-duration
     capture a faster run does more iterations, so an unchanged frame accrues more samples. The diff
     also warns that fixed-duration exec-sample deltas conflate per-op cost with throughput → use a
     fixed-iteration `bench` A/B for a clean per-op CPU comparison.

## 4. Optional — CI perf-gate

Fail a build on regression (non-zero exit), keyed on the diff:

```bash
java -jar "$JVMLENS" analyze "$after" -b "$before" -a com.example.app \
     --assert "gc-ms < 100, regression-pp < 5, new-hotpath-pp < 10"
```
Metrics: `gc-ms`, `gc-pct`, `alloc-pct`, `oldobj-delta`, `regression-pp`, `new-hotpath-pp`. Exit
1 on regression, 0 pass, 2 bad-args.

## 5. Pair with JMH — they answer different questions

| Tool | Answers |
|---|---|
| **JMH** (`-prof gc`) | the *number* — throughput (ops/s) and **absolute** allocation (B/op) |
| **jvmlens** | the *where/why* — which method/allocation-site, source-attributed, LLM-ready |

Always run both. Use JMH's `B/op` as the **absolute** allocation truth (see the gotcha below),
and jvmlens to find *what* to fix.

## 6. Gotchas (read before trusting a diff)

- **The diff anchors on ABSOLUTE weight, not share.** Share-only diffing inverts in an optimize
  loop: a site whose absolute bytes *fell* shows a *rising* share because the total shrank. The
  diff leads with absolute (`Totals: Allocation X → Y`, per-site `before → after` bytes, share as
  secondary), and **hedges** a site whose absolute Δ *opposes* the total (`possible sampling
  redistribution — total alloc fell N%`). So read the **absolute** number, not the share Δ. Still
  worth a final cross-check against JMH `-prof gc` (B/op) for the ground-truth allocation rate.
- **Few allocation samples → noisy per-site bytes.** A short trial yields few alloc samples; the
  diff/summary flags `⚠ Only N allocation samples`. Trust the **total** bytes, not a single
  site's Δ, on short runs.
- **A surprising leaf is visible in the digest.** The hot-path teaser lists the **top leaves with
  counts** + `⚠ diffuse` when no leaf dominates, so a 2/168 frame masquerading as the cost shows
  itself. For a genuinely surprising leaf, still confirm with `jfr print --events
  jdk.ExecutionSample`.
- **JMH names every fork's file `profile.jfr`** (in per-benchmark dirs), so a diff header can
  read `profile.jfr → profile.jfr` — keep the before/after dirs distinct and remember which is
  which. (`analyze` also accepts a **directory** and merges all per-fork `.jfr` under it.)
- The recorder's own sink (`file null`) is filtered out of External I/O — so an "I/O" line on a
  pure CPU/alloc microbenchmark would be a jvmlens bug, not your code.

## Leave the project self-serving

Add a **Profiling (jvmlens)** block to the project's `CLAUDE.md` so future sessions know the
loop (jar location, the capture command, the `-a` scope, the diff).

---

*Local overlays (optional): if a `LEARNINGS.md` or an `extensions/*.md` file sits next to this
skill, read it first — it carries machine-specific field experience and guidance that isn't part
of the published skill.*
