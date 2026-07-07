# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

jvmlens reads a JFR (`.jfr`) recording and emits a compact, **LLM-ready** markdown
summary — a multi-MB `jfr print` dump (~hundreds of K tokens) becomes ~400 tokens
of ranked, source-attributed signal (hot paths, allocation sites, lock contention,
GC pressure, a heuristic cause). The summarizer is the product; capture and parsing
are JDK-provided (`jdk.jfr`). See `DESIGN.md` (the "why") and `ROADMAP.md` (the plan).

## Build & test

Use the Maven wrapper (`./mvnw`); `mvn` works if installed. Java 17 bytecode; the build
runs on 17/21/25. This is a **Maven reactor** — `jvmlens-engine` / `-cli` / `-agent` /
`-jmh` modules under a parent pom. Run from the repo root (builds all modules in order).

```bash
scripts/dev-verify.sh                         # ⭐ format + full-reactor verify (what to run before a PR)
scripts/dev-test.sh SummarizerTest            # targeted test, any module (-pl to narrow)
scripts/dev-test.sh -pl jvmlens-engine FixHintsTest
./mvnw -q clean package                       # full reactor build (runs all gates below)
./mvnw -q -pl jvmlens-cli -am package         # build one module + what it depends on
./mvnw spring-javaformat:apply                # auto-fix formatting (dev-verify does this for you)
```

The build is **strict and will fail** on style/coverage, not just compile errors —
these all run as part of `package`:

- **spring-javaformat** + **checkstyle** + **PMD** run at the `validate` phase (before
  compile). Run `spring-javaformat:apply` to fix formatting; checkstyle/PMD config is
  in `checkstyle.xml`, `checkstyle-suppressions.xml`, `pmd-ruleset.xml`. Note
  spring-javaformat uses **tabs** for indentation.
- **JaCoCo**: `jvmlens-engine` (the substantive logic) holds the strict **≥80%** line gate;
  the thin front-end modules (`-cli`/`-agent`, mostly transport/bootstrap glue) hold a 50%
  rot-guard floor with the bootstrap classes excluded. The gate runs at the `verify` phase
  (so `scripts/dev-verify.sh` and CI both enforce it; a bare `mvn package` does not).

## Run

```bash
java -jar jvmlens-cli/target/jvmlens.jar analyze recording.jfr   # built CLI fat jar
./mvnw -q -pl jvmlens-cli spring-boot:run -Dspring-boot.run.arguments="analyze,recording.jfr"   # dev
```

To produce a recording to test against, `examples/Workload.java` plants one known
pathology per scenario (`cpu` / `alloc` / `lock`); see `examples/README.md` for the
record-then-analyze recipe and `examples/experiments.md` for the quality methodology.

## Architecture: one engine, thin front-ends

The whole design: a dependency-free **engine** plus thin front-ends, where *every capture
path produces JFR consumed by the same engine*.

- **Engine** (no Spring/picocli, only `jdk.jfr.consumer`): `Summarizer.analyze(Path, Scope)`
  reduces a recording (via inner `Aggregates`) to the render-agnostic **`ProfileSummary`**
  record; `Renderers` turns it into markdown / JSON (hand-rolled) / prompt, and into focused
  reports (`Report`: full/cpu/memory/locks/gc). `Scope` decides what counts as application
  code. Keep the engine framework-free so all front-ends reuse it.
- **CLI** (`Main` wires picocli into Spring; `JvmlensCommand` is the root):
  - `analyze <file.jfr>` — offline.
  - `profile <pid>` — local attach via `jdk.attach` + `jdk.management.jfr` MXBean
    (`LiveCapture`); `--engine async` uses ap-loader (native frames); `-w/--warmup`, `-k/--keep`.
  - `bench --main <class>` — no-JMH bench harness (`BenchCommand`): runs a workload's `main` in a
    warmup→timed loop, captures an in-process JFR over just the timed phase, and summarizes; `--cp`,
    `-w/--warmup`, `-i/--iters`, `--jfr`, `--no-analyze`.
  - `watch <pid>` — continuous JFR ring buffer; periodic, or dump-on-trigger via `WatchTrigger`
    (`--on-gc-ms`/`--on-cpu-pct`/`--on-old-objects`).
  - `trend <history.jsonl>` — reduces the agent's appended `History.Sample` time-series (the
    `history=` long-run mode) to a change-over-time digest (engine `History`; CLI parses via Jackson).
  - `control <control-file> <cmd…>` — appends an in-flight command to the agent's watched control
    file (start/stop/clear/dump, enable/disable, settings, interval, scope, topn, status); reads the
    agent's `<file>.status` back. The command logic is the `agent.AgentControl` state machine.
  - `mcp` — stdio MCP server (`McpServerCommand`) exposing `ProfileTools` as scoped tools
    (overview → hot_paths/hot_leaves/allocations/lock_contention) **plus a live `profile`
    tool**; reach a remote host via stdio-over-ssh. Serves data only, never calls an LLM.
  - Shared output options (`-f/--format`, `-r/--report`, `-a/--app-package`, `-x/--exclude`)
    live in one `OutputOptions` `@Mixin`.
- **Agent** — `jvmlens-agent.jar` (`agent.JvmlensAgent`, Premain/Agent-Class) runs in-process,
  writing periodic summaries to a file; `snapshot=Class#method` captures **variable snapshots**
  (ByteBuddy advice → `snapshot.*`). The v2 (correctness) axis.
- **Deploy** — `deploy/helm/jvmlens` (standalone chart) + `scripts/deploy-agent.sh` attach the
  agent to any JVM image without touching the app's own chart.

Adding a command = a new `@Component @Command` registered in `JvmlensCommand.subcommands`;
keep analysis logic in the engine, not the command.

## Conventions

- **Engine stays dependency-free** (only `jdk.jfr.consumer`); CLI/MCP/agent are thin
  front-ends; every capture path produces JFR consumed by `Summarizer`.
- **Application-frame attribution**: lead with the first application frame. `Scope` skips the
  JDK + common frameworks + native frames (`::`, `.so`); `-a/--app-package` is include-only,
  `-x/--exclude` adds prefixes; the dominant app package is auto-detected and surfaced.
- **Trust signals are first-class** (user calls this essential): every ranked row shows its
  absolute hit `count`; sections are tagged `[sampled]` (statistical) vs `[measured]` (exact —
  locks/GC); a `⚠` caveat fires under 200 execution samples.
- **The heuristic under-interprets** — hedges, no confident "leak" from sparse old-object
  samples. Give the LLM clean data over a confident wrong label.
- **Build gates**: spring-javaformat + checkstyle + PMD run at the `validate` phase; the
  JaCoCo ≥80% line gate runs at `verify` (use `scripts/dev-verify.sh` or `mvn verify`, not a
  bare `package`). Run `spring-javaformat:apply` first. Transport/bootstrap classes (`Main`,
  `McpServerCommand`, agent, snapshot glue) are jacoco-excluded.
- **Tests** synthesize real recordings via `jdk.jfr.Recording`/attach at runtime — no committed
  `.jfr` fixtures.
- **Dogfood loop**: profile real projects → file `field-finding` issues (`scripts/field-finding.sh`)
  → fix → revalidate. Methodology: small CPU/memory/wait workloads, not giant cold inputs.
- **Infra**: k3s is managed via kubectl/helm (ns `unitrack`); **Portainer is only the Docker
  host**; images live in the Zot registry `registry.example.com:5000` (pull secret `my-regcred`).

## Gotchas

- **PMD bans `synchronized`** (method and statement) → use `ReentrantLock`.
- **picocli enums are case-sensitive** → `setCaseInsensitiveEnumValuesAllowed(true)` (Main sets
  it; standalone-CommandLine tests must too).
- **Agent dump on exit**: use JFR `setDumpOnExit`, not a shutdown hook (the hook races JFR's
  own teardown).
- **Self-attach** (ByteBuddyAgent / async-profiler into a child) needs
  `-Djdk.attach.allowAttachSelf=true`; CI may still block agent load → guard such tests with
  JUnit `Assumptions.abort`.
- **Agent/JMH jar packaging**: each is a *separate module* whose **main** artifact is the
  shaded jar — `jvmlens-agent` bundles the whole `jvmlens-engine` + relocated `net.bytebuddy`
  (+ the agent manifest); `jvmlens-jmh` bundles engine + profiler (jmh-core provided). Keep
  shade out of the `jvmlens-cli` (Spring Boot) module — co-locating them is what double-packed
  Spring and broke the Boot 4 fat jar.
- **Shade + ByteBuddy is multi-release** (#68): shade relocates the base classes' paths but
  **NOT** the `META-INF/versions/N/net/bytebuddy/**` copies — leaving entries whose path is
  `net/bytebuddy/...` while their bytecode is relocated → `NoClassDefFoundError` "wrong name"
  that crashes any ByteBuddy/Hibernate host app. The agent shade drops them (global filter
  `META-INF/versions/**` + `Multi-Release: false`; base classes are functional on 17/21/25).
  A `verify`-phase antrun gate fails if **any** `net/bytebuddy/` entry survives the agent jar.
- **CI workflow** needs `permissions` (checks/PR/contents write) and `continue-on-error` /
  `fail_ci_if_error:false` on reporting steps; surefire fork pinned `-Xmx512m`.

## How this file evolves

This file maintains a living **Decisions & Learnings** log. Append an entry whenever a
non-trivial decision is made, durable feedback is given, a non-obvious gotcha is found, a
convention is set/revised, scope shifts, or a dependency changes. Don't log routine code
changes (git has them) or anything obvious from reading the code now.

Entry format (the lint hook enforces it; body ≤500 chars):

```
- YYYY-MM-DD — **topic-tag** — what was decided. Why: the load-bearing reason. [see → docs/decisions/...]
```

New entries go in **Recent**. When a `**topic**` recurs 3+ times and stabilizes (latest
≥14 days old, uncontradicted), graduate it to a one-line rule and strike the source
entries. Reversals get `~~struck~~` with a follow-up, never silent deletion. Quarterly,
run `.claude/skills/evolving-claude-md/archive-decisions.py --cutoff YYYY-MM-DD --apply`
to move old entries to `docs/decisions/`. Hooks (audit/lint) live in `.claude/`.

### Decisions & Learnings (Recent — last 14 days)

- 2026-06-26 — **jmh-integration** — `analyze <dir>` merges JMH per-fork JFRs (#39 gap 2a); `JvmlensProfiler` ExternalProfiler invoked by FQN `-prof org.alexmond.jvmlens.jmh.JvmlensProfiler` (no ServiceLoader). #48 / #39 gap 2b.
- 2026-06-26 — **fix-hints** — `analyze --hints` (opt-in) appends `Likely fix directions [possible]`; tags each direction **structural** (mechanical-safe) vs **inherent** (parity-sensitive), structural-first. #39 gap5, #53 item2.
- 2026-06-26 — **token-budget** — `analyze --top-k <n>` (rows/section) + `--max-tokens <n>` (shrink top-k until ~chars/4 fits) via `RankLimits.set("all",…)`; the budget loop re-analyzes per candidate k. #39 gap 6.
- 2026-06-26 — **lab-leak-scan** — lab-leak prevention is the **global `lab-leak-guard` skill** + a global git pre-commit hook, not per-repo files. This repo carries only `.github/workflows/lab-scan.yml` (self-contained; reads denylist/baseline from CI secrets). The denylist + per-repo baselines live in **infra**, never the repo — they name the lab / list the leaked values, so they're themselves sensitive. Why: a repo-side denylist re-leaks what it guards. #54 scrubs the existing debt.
- 2026-06-27 — **release-scope** — 0.1.0 Maven Central scope = parent + **`jvmlens-engine` only** (the dependency-free lib); cli/agent/jmh set `maven.deploy.skip=true` and ship as **GitHub release assets** (`maven_release.yml` now attaches them via `files:`). Why: cli is a Spring Boot fat jar and agent/jmh are shaded tool jars — not useful as Central deps. Antora docs + README refreshed for the reactor + new surface (bench, line-anchoring, etc.).
- 2026-06-27 — **line-anchoring** — ranked rows now carry the JFR source line as a teaser: hot-path leaf-distribution leaves show `method:line`, hot-leaf rows `(line N)`, alloc sites prefix `:line` (dominant line per method via a histogram — weighted by bytes for alloc; degrades when absent). Keys stay `Type.method` so diffs and the #53-item-3 diffuse logic are untouched (line is display-only). #87 (roadmap-panel bet 2). Why: collapses an agent's diagnose→locate→edit into one step.
- 2026-06-27 — **profiler-baseline-loop** — `JvmlensProfiler` gains `keep=<path>` (retain the fork JFR) + `baseline=<prev.jfr>` (prints the **diff**, not the summary) → run→"what changed vs last run" in one JMH command (#50 item 2). New `ProfileSummary.allocSamples` drives a low-sample ⚠ caveat in both the summary's allocation section and `ProfileDiff` when <200 (#50 item 3). Closes #50. Why: total bytes are reliable but per-site byte splits/deltas are noisy on short trials, and the optimize loop shouldn't need a separate `analyze`.
- 2026-06-27 — **bench-harness** — `jvmlens bench --main <class> [--cp] [-w warmup] [-i iters] [--jfr] [-- args]` runs a workload's `main` in a warmup→timed loop, captures an **in-process** JFR (`settings=profile`) over *just* the timed phase, and summarizes — the no-JMH driver for ordinary apps. Closes #53 (item 5; all 8 done). Why: every consumer project hand-rolled the same load→warm→time→emit driver; `--cp` loads the workload via URLClassLoader so it needn't be on the jvmlens classpath.
- 2026-06-27 — **deploy-overlay** — lab deploy specifics moved to the private **`jvmlens-deploy`** Forgejo overlay (`homelab-deploy-overlay` skill); public keeps generic arg-driven scripts, `values-homelab.yaml` relocated. #54 closed: history rewritten (`filter-repo` mirror, force-push) — dropped the committed denylist *catalog* files + replaced lab tokens across all 141 commits; 0 hits across every ref. Why: lab topology must never reach a public repo, at HEAD *or* in history.
- 2026-06-27 — **leaf-confidence** — hot-path teaser now shows the **top 3 leaves with counts** (`leaf c/total · …`) aggregated across the path's samples (`leafByApp`), replacing the single first-seen call-path teaser (`appStack`/`topFrames`, both removed). Flags `⚠ diffuse — no leaf >20% of path` when no leaf holds `LEAF_CONFIDENCE`. Why: the first-seen leaf misled (jhelm: a 2/168 `URLClassPath.getResource` hid the real Jackson cost); show where time *actually* goes. #53 item 3.
- 2026-06-27 — **skip-warmup** — `analyze --skip-warmup <ms>` drops events in the first `<ms>` of each recording so hot paths reflect **steady state**, not JIT/classload churn (#53 gap 4). Engine `analyze(files, scope, source, skipWarmupMs)` computes a **per-file** cutoff (each JMH fork's own earliest event, via a cheap timestamp pre-pass) and skips events before it; the source label notes `(warmup Xms skipped)` for transparency.
- 2026-06-27 — **cause-magnitude** — `Summarizer.suspectedCause` (extracted to a pure, unit-tested method over a `CauseSignals` record) weighs each dimension by **magnitude**, not presence: a lock is the headline only if its blocked time is substantial AND exceeds est. CPU work (samples × ~10ms) + GC; else it's demoted to a hedged `Minor lock contention …` note and the headline leads with the dominant hot path (+ top allocation). Why: a 62ms lock outranked a 7% hot path + ~200MB alloc (#67).
- 2026-06-27 — **fail-open** — every agent advice carries `suppress = Throwable.class` (ByteBuddy bytecode catch, incl. `Error`/`StackOverflowError`) + `probe.FailGuard` per-dimension circuit breaker wrapping each `*Store.record` (contain → count → disable the dimension after 5 failures). So an agent bug can never crash the host (the #68 Bug 2 blast radius). #73 item 1. Why: a monitoring agent must degrade monitoring, not take down the app it observes. (`onThrowable` ≠ `suppress`.)
- 2026-06-26 — **agent-host-bugs-68** — #68 agent-vs-host: shade left un-relocated `META-INF/versions/` shadowing host ByteBuddy (drop MR copies); `SqlSanitizer` regex SOE on long literals (cap MAX_SCAN=512). New `jvmlens-it` gate (#72).
- 2026-06-26 — **multi-module** — Maven reactor (engine/cli/agent/jmh) — fixes shade+repackage double-pack (fatal on Boot 4 / Spring 7 classloader). Bumped Boot 4.0.7 + Jackson 3. One jar verified JDK 17/21/25.
- 2026-06-26 — **diff-redistribution** — `ProfileDiff` hedges an allocation-**site** row whose absolute Δ *opposes* the total-allocation Δ with `(possible sampling redistribution — total alloc fell N%)` — JFR reattributes sampled weight to the next site as the dominant allocator shrinks. **Annotate, never suppress/re-rank** (a real localized regression looks identical; absolute anchor #43/#44 stays legible). Closes #52, covers #53 item 8 (`PerfGate` has no per-site alloc gate to harden).
- 2026-06-26 — **alloc-crosstab** — top allocation **sites** now carry a per-**type** breakdown teaser (`byte[] 4.2 GB · String 2.6 MB`) on the top 2 sites (3 types each), via a nested `allocBySiteType` map populated in `addAllocation` and passed as the row `stack` teaser. `simpleType` decodes JVM array descriptors (`[B`→`byte[]`). Why: the two separate "top sites"/"top types" lists forced the reader to *infer* "floatString allocates 4.1 GB of String" — now it's stated. #53 item 1.
- 2026-06-26 — **profiler-strict-opts** — `JvmlensProfiler` now **hard-errors** (`ProfilerException` → JMH aborts with a did-you-mean) on an unknown option key / malformed pair instead of silently no-op'ing; also accepts `appPackages=` + comma (CLI `-a` parity), pairs split on `;` only. #53 item 6 / #50 item 1. Why: a misspelled `appPackage` used to emit an *unscoped* summary — silent misconfig that still produces *a* wrong result is the worst UX failure, costing a whole capture before you notice.
- 2026-06-28 — **source-echo** — `analyze --source <roots>` echoes each `file:line` anchor's source text inline (`floatString:129 ⟶ mantissa.substring(…)`) via engine-side `SourceResolver.decorate` (no `Summarizer.analyze` signature change). Opt-in, comma/path-sep roots, degrades silently (file-not-found → un-annotated, never fabricates). Why: a coding agent sees the offending line without opening the file. gotmpl4j field-finding #100 item 3.
- 2026-06-28 — **dispersion-verdict** — `JvmlensProfiler` measured A/B (#104) also prints a **dispersion** line (`dispersionNote`): a real structural alloc removal collapses the cross-fork variance band (`±17,200→±35, ~500×`) — a signal the mean can't give — and near-deterministic bytes/op emits a STOP (pivot off alloc). `ProfileDiff.redistributionNote` gained a noun arg so the #52 hedge now also annotates **CPU** hot-path ▲ rows opposing the exec-sample total. #110.
- 2026-06-28 — **throughput-verdict** — `JvmlensProfiler` `baseline=` now also prints a measured **throughput** A/B (`throughputVerdict`, the CPU analog of #104) gating on JMH's exact primary score; flags a sampled hot-path share that moved while throughput is flat ("a CPU-share shift is not a speedup"). The `<keep>.bop` sidecar became a `key=value` line recording the **benchmark name** (+`Measured` holder), so a `baseline=` from a *different* method is detected → warn+skip, not confident-wrong numbers. gotmpl4j #112.
- 2026-06-29 — **central-publish-exclude** — `central-publishing-maven-plugin` **ignores `maven.deploy.skip`** (that flag only governs `maven-deploy-plugin`), so "skipped" modules still publish to Central. It's an aggregator extension uploading one bundle at the **last** reactor module, so per-module `<skipPublishing>` on the last module (jvmlens-it) risks skipping the whole upload. Right lever: `<excludeArtifacts>` on the **aggregator** config (parent release profile). cli/agent/jmh kept published. #117.
- 2026-06-29 — **regex-hoist-hint** — `FixHints` regex rule sharpened (#119): fires on `java.util.regex.Pattern.{compile,clazz,expr}` leaves OR a `String.replaceAll(String,…)`/`matches`/`split`/`replaceFirst` source line (the silent per-call recompile), and names the concrete fix — hoist the `Pattern` to a `static final` field + reuse `Matcher` (structural). Why: regex-in-a-hot-path is a top recurring Java perf bug; jvmlens had every signal but emitted only the generic "allocation" direction.
- 2026-06-29 — **flat-total-cpu-hedge** — `ProfileDiff` CPU hedge extended to the **flat-total** case (#122): #110 only fired when exec-sample total moved ≥5% and a row opposed it; now when the total is ~flat a material ▲ is hedged as redistribution (a larger share of a conserved total, not more work). Plus a one-line caveat that fixed-duration exec-sample deltas conflate per-op cost with throughput → use a fixed-iteration `bench` A/B. Alloc diffs unchanged (flag is CPU-only). Gated on a real mover so a no-op diff stays quiet.
- 2026-06-29 — **scope-exclude-wins** — `Scope.isApplication`: `-x` excludes now apply **even inside an `-a` include** (exclude checked first) — includes used to short-circuit and ignore excludes, leaving an excluded test frame in the roll-up (#121). Also: `Summarizer.ioTeaser` flags a **0-byte / single-op / >1s-blocked** endpoint as a likely child-process/pipe wait, not a network peer. Doc TIP: for surefire forks prefer `profile <pid>` (the fork is killed, not exited → `dumponexit` writes no JFR). #121.
- 2026-07-01 — **cpu-diff-disproportionate-hedge** — `ProfileDiff` CPU hedge now covers a `▲` row that **outpaced a modestly-rising** exec-sample total (share climbed): #110 only hedged rows opposing the total, #122 only the ~flat-total case, so a fixed-duration capture whose total rose 13% while freed frames rose 60% (share 25%→36%) went un-hedged. New same-direction share-shift (≥5pp) per-row hedge + a `disproportionateShiftCaveat` pointing at a fixed-iteration `bench` A/B. Alloc diffs unchanged (CPU-only). Also backfilled the missing 0.2.1 CHANGELOG section. #127.
- 2026-07-01 — **trend-restart-segmentation** — `History.digest` splits a multi-lifetime run at inter-sample gaps ≥2.5× the median (a probable redeploy — the agent appends on a fixed interval, so a replaced pod leaves a big hole; no explicit uptime/bootId field, so the `t`-gap is the signal), drops each lifetime's first (cold-start-burst) window from the steady-state aggregates, adds a `## Lifecycle` note, and scopes the retention indicator to the latest lifetime (old-object counts reset on restart). Backward-compatible: works on already-collected history, single gapless runs unchanged. #129.
- 2026-07-01 — **exclude-alloc-types** — `-x/--exclude` now also folds the **Top allocated types** block: types whose element package matches an exclude (array descriptors decoded via `baseTypeName`) roll up into one accounted `«excluded types (-x)»` row (`Teasers.foldExcludedTypes`), so a test capture's embedded-DB internals (in-process H2 MVStore types) don't crowd out app types. Reused `-x` rather than a new `--infra-package` flag (#128 proposed the flag; `-x` already scopes sites/hot-paths, and the types block was the only one ignoring scope). Moved `simpleType`/`arrayBase`/fold helpers Summarizer→`Teasers` to stay under the 800-line checkstyle cap. #128.
- 2026-07-02 — **agent-launch-scope** — agent `scope=app:<prefix>`/`exclude:<prefix>` launch arg pins app attribution from sample 1, no control channel. Why: replays as in-flight `scope` cmds via `AgentControl.apply`. #133.
- 2026-07-07 — **rule-harness** — new `test/.../harness/` (`Summaries` builder + `RuleDetectionHarness` table runner): a fixture = model-synth scenario → `mustContain`/`mustNotContain` per render mode; false-positive guard = `mustNotContain` on a look-alike. Why: uniform, cheap backfill of detector coverage from field-findings.
- 2026-07-07 — **db-anchor-hints** — P1a: `db` section feeds `--hints` (N+1→batch, `SELECT *`→project) via section-scoped `FixHints` rules (`sectionKey` keeps SQL text off code rules); `SqlStore` captures first app-frame call-site (bounded, scope-gated, fail-open `StackWalker` → `· at Repo:88`), `SourceResolver` echoes it. Why: semantic dims must meet the CPU/alloc form-factor bar. Shared walk reused by P1b/c/d.
- 2026-07-07 — **web-anchor-hints** — P1b: extracted the call-site walk to shared `probe.CallSites` (one `setAppScope`, wired once in the agent); `WebStore` anchors each endpoint to its handler + flags `high error rate` (≥20% over ≥10 reqs), a section-scoped web `FixHints` rule. `FixHints.dbRule`→generic `sectionRule(key,…)`. Why: DRY the shared mechanism at its 2nd consumer; slow-endpoint is P2 correlation, not a mechanical hint.
- 2026-07-07 — **cache-anchor-hints** — P1c: shared `OpStore` gains call-site capture (`record(label,nanos,site)`; benefits cache+messaging); `CacheAdvice` captures `get` return (null=miss) → `CacheStore` tracks hit/miss + flags `low hit rate` (<50% over ≥20 gets), a section-scoped cache hint; op anchored to its app caller. Why: hit rate is the crisp cache lever; hit/miss stays in the cache layer so `OpStore` stays generic.
- 2026-07-07 — **mongo-dimension** — P5a: new opt-in `mongo` dimension. `MongoCapture` matches `com.mongodb.client.MongoCollection` by name (no Mongo compile dep); `MongoAdvice` `@Origin("#m")`; `MongoStore` is `OpStore`-backed → inherits P1 anchor + P2b linkage + correlation free. `flag`: repeated `find`/`aggregate` → N+1 document fetch; repeated single-doc `insertOne`/`updateOne`/`deleteOne` → un-batched. Why: find/aggregate latency is construction-only, so **count** is the N+1 signal. P5b Redis next.
- 2026-07-07 — **orm-nplus1** — P3 sharpens `db` N+1 by SQL shape (`SqlStore.ormFlag`, keyed on the lowercased sanitized statement): a repeated `insert`/`update` reads as **un-batched writes** → new section-scoped hint (`hibernate.jdbc.batch_size` + `order_inserts`); a fast repeated `select` is possible N+1 **unless** it already uses ` in (?)` (the batch-fetch fix) → suppressed. Why: writes were silent and an already-batched `IN` select was a false N+1. Exact-count (JFR shows N individual executes when batching is off).
- 2026-07-07 — **callpath-linkage** — P2b: agent captures a bounded (≤8-frame) dominant app call-path per deeper op (`CallSites.capturePath`); its outermost app caller becomes a `↳ under <EntryClass>` marker on db/cache/messaging rows. `Renderers.confirmedChains` flips P2a's co-occurrence to **✓ Confirmed chain** when a deeper op's entry class == the endpoint's handler-anchor class. Degrades to P2a offline. Why: turns co-occurrence into proof where the stack shows it. Defaults: agent-side, strict, N=8, dominant-path-only.
- 2026-07-07 — **correlation-anchors** — P2a: `Renderers.appendCorrelation` now renders an ordered causal chain (endpoint→SQL→cache→messaging→I/O→hot path→lock→GC), each link showing its P1 source anchor (`@ UserRepo:88`) + a compact flag (N+1 / low hit rate / sync send), weaving all agent dims. Stays honest ("co-occurrence, not proof"); startup softening kept. Why: co-occurrence with *where to look* is the actionable form; P2b (call-path linkage) will flip the hedge to "confirmed chain" where provable.
- 2026-07-07 — **messaging-anchor-hints** — P1d closes P1's per-dim sweep: `MessagingStore` anchors each op + flags `synchronous per-message send` (a `.send` with ≥50 calls & avg ≥2ms — gated so Kafka's fast async sends don't trip), a section-scoped messaging hint. Added `OpStore.sections(key,title,OpFlag)` so a dim adds its own per-op flag without duplicating counters or polluting `OpStore`. Why: only crisp mechanical messaging lever; consumer-lag needs data JFR doesn't carry.
- 2026-Q2 — **archived** — 12 entries → docs/decisions/2026-Q2.md.

### Historic

Earlier build-out entries (v0.1→v0.2: engine, outputs, profile/watch/mcp, scope, async,
agent, ci) graduated into Architecture / Conventions / Gotchas above. Full chronology:
`docs/decisions/2026-06-jvmlens-buildout.md` (and git log).
