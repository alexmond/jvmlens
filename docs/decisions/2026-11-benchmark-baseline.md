# Design: a small committable baseline for JMH runs (#200)

Status: **proposed** — to be agreed before #201 (`gate=`) and #202 (runner) build on it.
Epic: #204. Date: 2026-11-24.

Each claim is tagged **[verified]** (I read the code or ran it) or **[assumed]** (not checked).

## Decision (short)

1. **Slice A — do it.** One committed text file for all benchmarks, one line per JMH
   benchmark. A line is today's `.bop` sidecar line plus identity and environment keys, so it
   uses the same `key=value` parser. About 250–350 bytes per benchmark.
2. **Allocation (bytes/op) gates by default. Throughput gates only when the environment
   matches** (see open question 1). Within one run, the JMH error band is a fair test. Across
   machines it does not cover the machine-to-machine spread.
3. **Slice B — defer.** Reading a summary back needs a JSON reader *and* changes to the JSON
   writer, because today's JSON loses data the gate needs. It adds about 9 KB per benchmark to
   gate on sampled data, which the project's own rules say to annotate, not gate.
4. **A stale or missing baseline never passes silently.** A file problem stops the run at
   startup. A per-benchmark mismatch prints a loud `⚠` line and gives no verdict. With
   `gate=`, it is a "cannot evaluate" failure, never a pass.

## Slice A — the baseline file

### Where it lives

- One file per benchmarks module. Suggested name: `jvmlens-baseline.txt` at the module root
  (next to the benchmarks `pom.xml`). The path is always given explicitly. jvmlens does not
  look for it in default places, so a wrong working directory is an error, not a quiet "no
  baseline".
- **One file for all benchmarks, not one per benchmark.** Reasons:
  - a whole-set check is possible ("this benchmark is not in the baseline");
  - JMH ids with params make long, awkward file names;
  - one reviewable diff when it is regenerated.

### Format (v1)

- UTF-8 text. **Line 1 must be exactly `# jvmlens-baseline 1`.** Other `#` lines are comments.
  Blank lines are ignored.
- One benchmark per line, as space-separated `key=value` tokens. Every value is URL-encoded
  (UTF-8) when written and decoded when read, so a `@Param` value with spaces or `=` is safe.
  Today's sidecar values never contain `%` or spaces, so the existing sidecar parser still
  reads them unchanged.
- Lines are sorted by `id`, so a regeneration gives a stable diff.
- Numbers are written with `Double.toString` (this round-trips exactly).
- **A missing error term is written as `nan`, never `0`** (see "Missing band" below).

| Key | Meaning | Source (JMH API) | Role |
|---|---|---|---|
| `id` | lookup key | `BenchmarkParams.id()` | **match** — opaque, never parsed |
| `benchmark` | `pkg.Class.method` | `getBenchmark()` | display, and the "nearest" hint |
| `mode` | `thrpt` / `avgt` / `sample` / `ss` | `getMode().shortLabel()` | must match; sets the direction |
| `params` | `k:v,k:v` (sorted, each part URL-encoded) | `getParamsKeys()` / `getParam()` | display, and the "nearest" hint |
| `threads` | thread count | `getThreads()` | must match |
| `mforks` | measured forks merged | profiler (#191) | see open question 2 |
| `tput`, `tputerr`, `tputunit` | primary score, its error, its unit | `getPrimaryResult()` | the throughput verdict |
| `bop`, `boperr` | bytes/op and its error | `gc.alloc.rate.norm` | the allocation verdict (`-prof gc`) |
| `jdk` | JDK version of the forked JVM | `getJdkVersion()` | feature version must match |
| `arch`, `cpus` | `os.arch`, `availableProcessors()` of the host JVM | system properties | must match for throughput to gate |
| `iters` | `<warmup iters>x<time>/<measure iters>x<time>` | `getWarmup()` / `getMeasurement()` | if different: a `⚠` note only |

Notes on the keys:

- `tput*` keeps the sidecar's names even in `avgt` mode, where the number is a time. Keeping
  the names lets the existing `Measured` mapping read both files.
- **Why both `id` and separate keys [verified, JMH 1.37 source]:**
  - `id()` is `URLEncode(benchmark) + "-" + mode + ("-" + key + "-" + value)*`, with params in
    sorted order (`WorkloadParams` is a `TreeMap`). It is unique for a run, but it cannot be
    parsed back, because `URLEncoder` leaves `-` as is.
  - It also does not include threads or the time unit.
  - So `id` is used only as a lookup key, and the parts that need checking are stored as
    their own keys.

Example. The id shape comes from reading the source, not from a real run; the numbers are
made up.

```
# jvmlens-baseline 1
# Regenerate with the profiler's saveBaseline= (or the #202 runner's regenerate mode) on the CI runner class.
id=demo.ParseBench.parse-Throughput-size-1000 benchmark=demo.ParseBench.parse mode=thrpt params=size:1000 threads=1 mforks=3 tput=48211.7 tputerr=912.4 tputunit=ops%2Fs bop=1048.02 boperr=0.31 jdk=21.0.5 arch=amd64 cpus=4 iters=5x10s%2F5x10s
id=demo.ParseBench.parse-Throughput-size-10 benchmark=demo.ParseBench.parse mode=thrpt params=size:10 threads=1 mforks=3 tput=1903344.2 tputerr=40211.9 tputunit=ops%2Fs bop=96.0 boperr=0.0 jdk=21.0.5 arch=amd64 cpus=4 iters=5x10s%2F5x10s
```

### Profiler options (new keys)

- `baselineFile=<path>` — compare each trial against its line in this file. It prints the
  measured throughput and allocation verdicts (the same math as today's `baseline=`) next to
  the plain summary. This works without any `.jfr`.
- `saveBaseline=<path>` — after each trial, **upsert** this trial's line: replace the line
  with the same `id`, or add it. The file is re-sorted and written atomically (temp file,
  then move).
  - Lines for benchmarks that are not in this run stay. This makes a subset regeneration
    (`-e`, a regex) safe.
  - The #202 runner's regenerate mode writes to a fresh file instead, so its result is
    exactly the benchmarks in that run.
  - When saving, it warns if `-prof gc` is off (no `bop`) or `mforks < 2`.
- Both keys may be given together. The comparison uses the file as it was before this
  trial's write.
- The existing `baseline=<.jfr>` and `keep=` stay as they are (the local A/B loop). These are
  new keys, not a second meaning of `baseline=`. A key that means "a `.jfr` or a text file,
  depending on what the file contains" is the kind of implicit behaviour CLAUDE.md warns
  about.
- Check against #199's keys before adding these to `KNOWN_KEYS`. When I looked, Lane B had
  no commits on `feat/199-jmh-out`.

### Matching rules (in order)

1. Find the line whose `id` equals `BenchmarkParams.id()`. Mode and params are part of the
   id, so a different `-p size=…` or `-bm` simply finds no line.
2. If no line is found, print the **nearest** line for the same `benchmark` (different params
   or mode), so the user sees why it did not match.
3. On the matched line, `threads`, `tputunit` (changed by `-tu`), and the feature version of
   `jdk` must all match. Otherwise the line is **stale**.
4. `arch` and `cpus` must match for the **throughput** verdict to gate. The allocation
   verdict does not need them.
5. If `iters` differs: print a `⚠` note and still give the verdict.
6. `mforks`: see open question 2. The proposal is that it must match for `gate=`.

### Regression direction (needed by #201)

`throughputVerdict` does not say which direction is better [verified]. A gate does need it:

| Mode | Score unit | Worse means |
|---|---|---|
| `thrpt` | ops/time | score **down** |
| `avgt`, `sample`, `ss` | time/op | score **up** |
| any (allocation) | B/op | `bop` **up** |

### Missing band — a silent zero today [verified]

- JMH returns `NaN` for the score error when a result has ≤ 2 iterations in total
  (`AbstractStatistics.getMeanErrorAt`: `if (getN() <= 2) return Double.NaN`).
- `JvmlensProfiler.errOrZero` turns `NaN` into `0`, and `sidecar` writes that `0`.
- So the noise band becomes 0%, and `throughputVerdict` / `allocVerdict` call **any** change
  SIGNIFICANT.
- The rule for the new file:
  - write `nan`, not `0`;
  - a verdict with `nan` on either side says "no error band";
  - `gate=` treats `nan` as **cannot evaluate**, not as a pass or a fail.
- Separate case: `boperr=0.0` can be a real result (allocation that is truly the same every
  op). The floor and dispersion logic from #192 / #110 already handles that, so it is not
  treated as missing.

## Slice B — hot paths without a `.jfr`: defer

What it would take [verified by reading `Renderers.json` and running `analyze -f json`]:

- **The JSON writer loses data the gate and diff need:**
  - There is no `allocBytes` field. A baseline read back would have `allocBytes=0`, so
    `PerfGate`'s `alloc-pct` computes `∞` and **fails every time**. `ProfileDiff`'s
    allocation scalar would be wrong too.
  - It drops `Ranked.lineage`, which `HotPathMoves` needs. So the "moved from" detection for
    `new-hotpath-pp` (#190) would quietly stop working.
  - It drops `Section.unit` and rounds shares to 4 decimals.
- **It must be full-rank.** `PerfGate` needs `RankLimits.full` summaries (#165). Measured on
  `examples/Workload.java cpu 8`:
  - `.jfr`: 711 KB;
  - default JSON: 1.8 KB;
  - full-rank JSON: **9.4 KB, 83 rows**.
  - A real benchmark has more rows. Every app frame name would be committed.
- **Cost:**
  - a hand-written JSON reader in the engine (≈200–300 lines plus tests, to meet the 80%
    gate);
  - writer changes (`allocBytes`, lineage, unit);
  - and from then on, the summary JSON becomes an **input contract** that every future
    `ProfileSummary` field change has to stay compatible with.
- **Payoff is low:**
  - `regression-pp` / `new-hotpath-pp` gate on *sampled* shares. Across JIT runs and
    machines those shares move (inlining changes the leaf frame, #190). CLAUDE.md: diffs
    "only annotate".
  - The "where" still appears in CI without Slice B: each run's own summary is printed, and
    written by `out=` (#199). Only the *diff* of hot paths needs a baseline.
- **If it is ever needed:** don't write a generic JSON reader. Add optional `hot=` tokens to
  the same baseline line instead (`name:count` for the top hot paths, plus `exec=` for the
  sample total). That is everything `regression-pp` needs, it uses the same parser, and it
  needs no new contract. Move detection (lineage) would still be out of scope.

## Failure modes — how each one fails loudly

"Startup" means when the option is parsed (a `ProfilerException`, the same as an unknown key
today). **[assumed]** This stops the JMH run before any benchmark runs. #201 must check how
JMH reports a `ProfilerException` from the profiler's constructor, as #201 already asks.

| Situation | Without `gate=` | With `gate=` |
|---|---|---|
| `baselineFile` missing or unreadable | startup error | startup error |
| Line 1 is not `# jvmlens-baseline 1` (wrong file, or a newer version) | startup error naming the version found | same |
| A line that cannot be parsed, or a duplicate `id` | startup error naming the line number | same |
| `id` not in the file (new benchmark, other `-p`/`-bm`) | `⚠ not in baseline` + nearest line; summary only | cannot evaluate → **fail** |
| `threads` / `tputunit` / JDK feature version differ | `⚠ stale baseline (field: a → b)`; no verdict | cannot evaluate → **fail** |
| `arch` / `cpus` differ | allocation verdict; throughput shown as "different machine, not compared" | allocation gated; throughput **not gated**, and the output says so |
| `mforks` differs | verdict + `⚠ n differs` | **fail** as stale (open question 2) |
| `nan` band on either side | verdict says "no error band" | cannot evaluate → **fail** |
| No `bop` in the baseline, or no `-prof gc` in this run | "allocation A/B unavailable" (same as today) | **fail** if the gate covers allocation |
| A baseline line not covered by this run | the profiler cannot see this (one instance per benchmark) | #202 runner: a **warning** listing them (filters can pick a subset legitimately) |
| `iters` differ | `⚠` note | `⚠` note, still evaluated |

Exit codes stay as #202 has them: 1 = regression, 2 = cannot evaluate / bad config.

## Existing gap found while designing (relevant now)

`measuredHead` checks only `getBenchmark()` against the sidecar's `benchmark` [verified].
`getBenchmark()` contains no params and no mode. So today, with `baseline=<.jfr>`:

- a `@Param` benchmark compares each param combination against whichever combination last
  wrote `keep=` (one fixed path);
- a `-bm avgt` run compares against a `thrpt` sidecar;
- and both are reported as **matched**.

Fix: write `id=` (and `mode=`) into the `.bop` sidecar too, and compare `id` when it is
present. This is small; #201 can do it, or it can be its own ticket.

## Notes for the builders

- Code goes in `jvmlens-jmh`, not the engine. The file is keyed by JMH ids, and the engine
  stays JMH-free.
  - New `BenchBaseline`: parse, validate, look up, upsert/write.
  - Move the pure verdict methods (`throughputVerdict`, `allocVerdict`, `floorVerdict`,
    `dispersionNote`, about 200 lines) out of `JvmlensProfiler` into a helper first. It is
    754 lines, the cap is 800, and #199 adds to it too.
- Tests: each mismatch row in the failure table gets a case that fires and a look-alike that
  must stay quiet (CLAUDE.md test convention). The `nan` band case and an upsert keeping
  unrelated lines both need a test.

## Rejected alternatives

- **Commit the `.jfr`** — 711 KB for an 8 s toy workload [verified]; too big to commit.
- **Commit raw JMH JSON (OGNL)** — needs a general JSON reader. It carries per-iteration raw
  data (large, noisy diffs). And the profiler cannot write it, only JMH's `-rf json` can, so
  regenerating would need two tools.
- **One file per benchmark** — awkward file names from param ids, no whole-set view, many
  files in review.
- **A fixed % threshold (OGNL's 10%)** — ignores the error band jvmlens already computes.
- **A `.properties` file** (`<id>.tput=…`) — about 10 lines per benchmark instead of one, and
  a second parser.
- **Overloading `baseline=` to accept the text file** — the meaning would depend on the file
  content. Separate keys are explicit.
- **Slice B through summary JSON now** — see above: lossy writer, new input contract, gates
  on sampled data.

## Open questions for the user

1. **Throughput across machines (most important).** **[assumed]** The JMH error band does not
   cover the spread between two runs on different machines, or on two shared CI runners of
   the same class. I did not run an A/A test to measure it. Matching `arch`/`cpus` is
   *needed* to compare, but it is not *enough*. Options:
   - (a) gate allocation only, and only show throughput — the safest;
   - (b) gate throughput only when `arch`/`cpus`/JDK match (the proposal above), and say the
     baseline must be generated on the CI runner class;
   - (c) always gate throughput.

   Recommendation: (a) by default, with throughput gating turned on explicitly
   (e.g. `gate=alloc,tput`), and (b)'s environment check applied when it is on.
2. **Fork count mismatch.** Treat a different `mforks` as stale (proposed: strict, easy to
   relax later), or only warn? Related: should `gate=` require `mforks ≥ 2` on both sides? A
   single fork understates the band, and the profiler already warns about that. But
   requiring 2 forks doubles CI time.
3. **Upsert vs replace for `saveBaseline=`.** The proposal is upsert in the profiler, and a
   fresh-file replace in the #202 regenerate mode. With upsert, lines for deleted benchmarks
   stay until someone removes them. The runner lists them as a warning.
