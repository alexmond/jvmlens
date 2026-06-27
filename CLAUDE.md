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

- 2026-06-24 — **remote-direction** — ~~JMX (`--jmx`)~~ **removed**: never works easily (RMI/container pain), needs target start flags, async can't use it. Remote = run jvmlens *on* the host (ssh/kubectl/docker exec, or MCP over stdio-over-ssh). Open: an MCP-over-HTTP endpoint only if a multi-client sidecar needs it.
- 2026-06-24 — **v2-snapshots** — agent `snapshot=Class#method` captures variable snapshots (ByteBuddy → `snapshot.*`), digested into the summary. Open follow-ups: locals (need `-g`, detect-and-degrade), condition-gating, PII redaction for prod.
- 2026-06-24 — **deploy** — `deploy/helm/jvmlens` + `scripts/deploy-agent.sh` attach the agent to any JVM image as a *separate* release. Caveat: a profiled copy sharing the app's `envFrom` hits the **same DB** — point it at a throwaway DB or run read-mostly.
- 2026-06-24 — **validation** — blind A/B settled the whole-idea gate: 8/8 isolated LLM sessions named the planted bug; on the fair `lock` head-to-head the summary matched raw's diagnosis at 5.5× fewer tokens (cpu/alloc raw overflow). Why: the release precondition ("real benefits") is now evidence-backed, not asserted. [see → examples/experiments.md]
- 2026-06-24 — **adoption** — `INTEGRATING.md` (mirrored as `docs/.../integrating.adoc`) is the portable guide for profiling *other* projects — decision table over the 5 paths + a paste-ready CLAUDE.md snippet. Why: external adoption needs a task-oriented doc, not the flag reference.
- 2026-06-25 — **long-run** — agent `history=<file.jsonl>` *appends* one compact CPU/memory/wait sample per interval (vs overwriting `out`); `jvmlens trend <file>` digests a multi-day run (engine `History`; CLI parses JSONL via Jackson). Why: "let it run for days, then check" needs retained time-series, not just the latest window. Retention stays hedged — *possible* growth, never "leak".
- 2026-06-25 — **extended-profiling** — new track beyond CPU/mem/wait → web/db/messaging/IO/VT-pinning. Research + epics in `docs/competitor-analysis.md` + `docs/extended-profiling.md` (E1 JFR I/O+pinning → E2 ByteBuddy semantics → E3 consume OTel → E4 correlation). Why: the semantic signal is solved by APM/JProfiler/Glowroot — jvmlens's uncontested gap is the LLM-ready digest form factor. Non-goal: becoming an APM (no tracing/dashboards).
- 2026-06-25 — **sections** — extended dimensions ride a single `List<ProfileSummary.Section>` (key/title/unit/measured/rows) rendered generically by `Renderers`/report-focus, not new record fields per epic. A delegating 14-arg `ProfileSummary` ctor keeps old call sites. Why: make E1→E4 additive without churning the record signature or every test each time. [E1 #26: io + pinning shipped]
- 2026-06-25 — **java-version** — tests compile at **Java 17 source** (the build runs on 17/21/25), so Java 21 APIs (`Thread.ofVirtual()`) don't compile in tests — can't generate `jdk.VirtualThreadPinned` live; cover pinning via rendering/history tests, file-I/O for the io path.
- 2026-06-25 — **e2b-db** — agent `db` option instruments `java.sql.Statement.execute*` (ByteBuddy) → `SqlStore` aggregates by **sanitized** SQL shape (no literals/PII), rendered as a `db` Section via `ProfileSummary.withSections`. SQL from arg or `toString()` (driver-dependent; degrades to `?`). `SqlAdvice`/`SqlCapture` jacoco-excluded + shade-included like snapshot glue; `SqlSanitizer`/`SqlStore` unit-tested. [#28]
- 2026-06-25 — **e2a-web** — agent `web` option instruments `HttpServlet.service` (ByteBuddy, by-name jakarta+javax, request/response read **reflectively** so no servlet dep) → `WebStore` aggregates by `METHOD route-shape` (`WebSanitizer` ids→`{}`, query dropped) with error counts. Advice `catch (Exception)` not `Throwable` (PMD). Pattern for E2c/E2d: new package + shade-include + jacoco-exclude the advice/capture; agent merges all stores via `instrumentationSections()`. [#28]
- 2026-06-25 — **e4** — `Renderers.appendCorrelation` adds a hedged `Cross-dimension correlation` block (FULL only, ≥2 dims) co-locating top endpoint/query/IO/hot/lock/GC — co-occurrence not proof (no per-request trace). Report focuses `db`/`web` added; `History.Sample` gains `dbMs`/`webMs` + an `Application (web/db)` trend block. [#29]
- 2026-06-25 — **e2cd** — agent `messaging` (Kafka/JMS send+poll/receive) + `cache` (Spring `Cache` get/put/evict) options. Shared `probe.OpStore` aggregates by `Class.method` via `@Advice.Origin` (no reflection, version-agnostic); `MessagingStore`/`CacheStore` are thin static facades. Report focuses `messaging`/`cache`. Pattern: extract the common store once, dimensions = matcher config. [#28]
- 2026-06-25 — **e3-consume** — agent `micrometer` option reflectively reads `Metrics.globalRegistry` (`MicrometerSource`, jacoco-excluded glue) → `MetricsReader` summarizes timers into a `metrics` section; detect-and-degrade, no Micrometer dep. `MetricsReader.read(Object)` tested with Micrometer-shaped fakes. **Extended-profiling track E1–E4 all shipped** (#26/#28/#27/#29). OTel/Actuator HTTP-scrape still future. [#27]
- 2026-06-25 — **deadlock** — `DeadlockDetector` (java.lang.management, dependency-free) renders `ThreadMXBean.findDeadlockedThreads` as a `deadlock` Section; **agent-always-on** (cheap, top signal). Why ThreadMXBean not JFR: a true deadlock never acquires the monitor so `JavaMonitorEnter` never fires; and the bean only sees its own JVM → agent-only. Report focus + MCP `deadlock` tool. Tested with a real 2-thread deadlock. [#23]
- 2026-06-25 — **control-plane** — in-flight agent control via a **watched control file** (no ports/JMX): `agent.AgentControl` state machine + `ControlChannel` + `jvmlens control` CLI (reads `<file>.status` back). Commands: start/stop/clear/dump, enable/disable (lazy ByteBuddy), settings, interval, scope, topn (`RankLimits`). `paused`+`start`-after-warmup solves cold-start (#2). Why file not port: keeps the no-JMX stance.
- 2026-06-25 — **agent-shade** — ~~new engine classes the agent uses must be added to the agent-jar shade `<includes>` (lists engine classes explicitly), else `NoClassDefFoundError`.~~ **Superseded by [[multi-module]]** (2026-06-26): the `jvmlens-agent` module shades the whole `jvmlens-engine` artifact — no per-class include list to maintain.
- 2026-06-25 — **io-noise** — filter the JFR recorder's own sink from External-I/O (`Summarizer.isNoiseEndpoint`: null/blank/`file null`/`file unknown`/`unknown`/`*.jfr`), so a no-I/O microbenchmark stops reporting `file null` I/O and the correlation stops chaining it. Field-finding #39 gap 4 (gotmpl4j JMH). The one spot the engine over-correlated.
- 2026-06-25 — **diff** — `analyze --baseline before.jfr after.jfr` → `ProfileDiff` names what changed (per-section share Δ as `50%→8% (▼42pp)` + NEW/GONE, totals Δ), ranked by change size. The agent optimize→measure loop. Field-finding #39 gap 1.
- 2026-06-25 — **perf-gate** — `analyze --baseline … --assert "gc-pct < 10, regression-pp < 5, new-hotpath-pp < 20, gc-ms < N, oldobj-delta < N"` → `PerfGate` exits **1 on regression**, 0 pass, 2 bad-args. Backend-free CI gate (#39 gap 3). Open #39: JMH integration (2), fix-class hints (5), token budget (6).
- 2026-06-25 — **diff-absolute** — `ProfileDiff`/`PerfGate` now anchor on **absolute** weight (bytes/ms/samples) not share — share alone *inverts* in an optimize loop (shrinking total → falling site shows rising share). Added total `allocBytes` to `ProfileSummary` (last component + delegating ctors), an Allocation Totals line, and `alloc-pct` gate. Field-finding #43. Lesson: don't diff normalized shares when the denominator is what you're optimizing.
- 2026-06-26 — **jmh-dir** — `analyze <file|dir>` + `--baseline <file|dir>` accept a **directory** → `Recordings.expand` finds all `.jfr` under it, `Summarizer.analyze(List,scope,source)` **merges** the forks (JMH `-prof jfr` writes one per fork). `Recordings.label` falls back to `parent/name` when both args are `profile.jfr` (diff-header disambiguation, #43 req4). #39 gap 2 part (a).
- 2026-06-26 — **fix-hints** — `analyze --hints` (opt-in) appends a hedged `Likely fix directions [possible]` section: `FixHints` regex-catalog maps hot-frame/alloc shapes (DoubleToDecimal, `$ListItr`, ensureCapacity, BigDecimal, regex, autobox, reflect) to a one-line direction, grounded in the triggering row. Default output stays clean-data-only. #39 gap 5.
- 2026-06-26 — **token-budget** — `analyze --top-k <n>` (rows/section) + `--max-tokens <n>` (shrink top-k until ~chars/4 fits) via `RankLimits.set("all",…)`; the budget loop re-analyzes per candidate k. #39 gap 6.
- 2026-06-26 — **jmh-plugin** — `org.alexmond.jvmlens.jmh.JvmlensProfiler` implements JMH `ExternalProfiler` (arms JFR via `addJVMOptions`, prints the summary in `afterTrial`). Invoked by **FQN** `-prof org.alexmond.jvmlens.jmh.JvmlensProfiler` (JMH has no ServiceLoader for profilers — earlier #48 note was wrong). `jmh-core` is **provided** (not bundled); a 2nd shade execution attaches `jvmlens-*-jmh.jar` (engine+profiler only). Profiler jacoco-excluded. #48 (was #39 gap 2b).
- 2026-06-26 — **lab-leak-scan** — lab-leak prevention is the **global `lab-leak-guard` skill** + a global git pre-commit hook, not per-repo files. This repo carries only `.github/workflows/lab-scan.yml` (self-contained; reads denylist/baseline from CI secrets). The denylist + per-repo baselines live in **infra**, never the repo — they name the lab / list the leaked values, so they're themselves sensitive. Why: a repo-side denylist re-leaks what it guards. #54 scrubs the existing debt.
- 2026-06-27 — **skip-warmup** — `analyze --skip-warmup <ms>` drops events in the first `<ms>` of each recording so hot paths reflect **steady state**, not JIT/classload churn (#53 gap 4). Engine `analyze(files, scope, source, skipWarmupMs)` computes a **per-file** cutoff (each JMH fork's own earliest event, via a cheap timestamp pre-pass) and skips events before it; the source label notes `(warmup Xms skipped)` for transparency.
- 2026-06-27 — **cause-magnitude** — `Summarizer.suspectedCause` (extracted to a pure, unit-tested method over a `CauseSignals` record) weighs each dimension by **magnitude**, not presence: a lock is the headline only if its blocked time is substantial AND exceeds est. CPU work (samples × ~10ms) + GC; else it's demoted to a hedged `Minor lock contention …` note and the headline leads with the dominant hot path (+ top allocation). Why: a 62ms lock outranked a 7% hot path + ~200MB alloc (#67).
- 2026-06-27 — **fail-open** — every agent advice now carries `suppress = Throwable.class` (ByteBuddy bytecode catch, incl. `Error`/`StackOverflowError`) so an agent bug can **never** crash the host — the #68 Bug 2 blast radius (a sanitizer overflow crash-looped the app at EMF build). Plus `probe.FailGuard`: a per-dimension circuit breaker wrapping each `*Store.record` (contain throwable → count → disable the dimension after 5 failures, log once). Why: a monitoring agent must degrade monitoring, not take down the app it observes. #73 item 1. (Note: `onThrowable` ≠ `suppress` — the old advice had only the former.)
- 2026-06-26 — **sql-sanitizer-soe** — #68 **Bug 2** (agent crashes a Hibernate host at `EntityManagerFactory` build) was a **regex StackOverflow, not ByteBuddy**: `SqlSanitizer`'s `'(?:[^']|'')*'` recurses one stack frame per char, so a long quoted literal (a driver metadata SQL hitting the `db` advice during EMF build) blew the stack. Fix: cap scanned input (`MAX_SCAN=512`) before the regexes. Found + gated by the new **`jvmlens-it`** module — a real Spring Boot/JPA host launched under `-javaagent` against Testcontainers Postgres; reproduces agent-vs-framework issues the in-JVM unit tests miss. (`AgentIgnores` from the same PR is bonus hardening, not the fix.) #72.
- 2026-06-26 — **mr-shade-gap** — the shaded **agent jar crashed any ByteBuddy/Hibernate host** (`NoClassDefFoundError` "wrong name"): shade relocates base `net.bytebuddy` but leaves `META-INF/versions/*/net/bytebuddy/**` un-relocated (path `net/bytebuddy`, bytecode shaded), also shadowing the host's ByteBuddy. Fix: drop the MR copies (global filter `META-INF/versions/**` + `Multi-Release:false`; base classes work on 17/21/25). A `verify` antrun gate fails if any `net/bytebuddy/` survives. #68.
- 2026-06-26 — **multi-module** — split into a Maven reactor: `jvmlens-engine` (plain jar) / `-cli` (Spring Boot fat jar) / `-agent` (shaded javaagent) / `-jmh` (shaded profiler). Why: shade + spring-boot:repackage in **one** module double-packed Spring into `BOOT-INF/classes` *and* `/lib` — latent on Boot 3.4, **fatal on Boot 4** (MR fat jar + Spring 7 `MetadataReaderFactoryDelegate` classloader split → `IllegalAccessError`). One packaging plugin per module fixes it. Engine keeps 80%; front-ends a 50% floor.
- 2026-06-26 — **spring-boot-4** — bumped to **Spring Boot 4.0.7** (Spring Framework 7) — pulls byte-buddy 1.17.8 (helps JDK 25) + Jackson 3 BOM. Migrated to **Jackson 3** (`tools.jackson`): `trend` uses `tools.jackson.databind.ObjectMapper`; `mcp` drops its explicit `ObjectMapper` (no-arg `StdioServerTransportProvider()`) as MCP SDK 0.10.0 is still Jackson-2-bound. Unblocked by the [[multi-module]] split. Verified 143 tests + boot on 17/21/25.
- 2026-06-26 — **java-compat** — verified the **one** Java-17-bytecode jar builds + passes the full suite on JDK **17/21/25** (locally + a CI matrix; runtime check, not multi-target — compiler stays release 17, only the running JDK varies). Reporting/publish gated to the 17 lane; 25 is `continue-on-error` because `--engine async` self-skips there (async-profiler 3.0 predates 25 → future ap-loader 4.x). pom `jdk21-plus` profile (JDK≥21) adds `-XX:+EnableDynamicAgentLoading` to surefire. **One jar suffices — no multi-jar.**
- 2026-06-26 — **diff-redistribution** — `ProfileDiff` hedges an allocation-**site** row whose absolute Δ *opposes* the total-allocation Δ with `(possible sampling redistribution — total alloc fell N%)` — JFR reattributes sampled weight to the next site as the dominant allocator shrinks. **Annotate, never suppress/re-rank** (a real localized regression looks identical; absolute anchor #43/#44 stays legible). Closes #52, covers #53 item 8 (`PerfGate` has no per-site alloc gate to harden).
- 2026-06-26 — **hints-levers** — `FixHints` tags each direction **structural** (mechanical/safe — iterator+lambda alloc, presize, reflect, autobox) vs **inherent** (parity-sensitive — number→string formatting, BigDecimal precision), sorts structural-first with a legend, adds the captured-lambda shape (`$$Lambda`). Why: the hardest call in the optimize loop is *which lever is safe to pull* — `floatString` was #1 but inherent (wrong), `ListNode.iterator` #2 was structural (right). #53 item 2.
- 2026-06-26 — **alloc-crosstab** — top allocation **sites** now carry a per-**type** breakdown teaser (`byte[] 4.2 GB · String 2.6 MB`) on the top 2 sites (3 types each), via a nested `allocBySiteType` map populated in `addAllocation` and passed as the row `stack` teaser. `simpleType` decodes JVM array descriptors (`[B`→`byte[]`). Why: the two separate "top sites"/"top types" lists forced the reader to *infer* "floatString allocates 4.1 GB of String" — now it's stated. #53 item 1.
- 2026-06-26 — **profiler-strict-opts** — `JvmlensProfiler` now **hard-errors** (`ProfilerException` → JMH aborts with a did-you-mean) on an unknown option key / malformed pair instead of silently no-op'ing; also accepts `appPackages=` + comma (CLI `-a` parity), pairs split on `;` only. #53 item 6 / #50 item 1. Why: a misspelled `appPackage` used to emit an *unscoped* summary — silent misconfig that still produces *a* wrong result is the worst UX failure, costing a whole capture before you notice.

### Historic

Earlier build-out entries (v0.1→v0.2: engine, outputs, profile/watch/mcp, scope, async,
agent, ci) graduated into Architecture / Conventions / Gotchas above. Full chronology:
`docs/decisions/2026-06-jvmlens-buildout.md` (and git log).
