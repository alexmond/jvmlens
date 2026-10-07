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

- **Skills** — `plugins/` is a Claude Code plugin marketplace (`jvmlens-perf`, `jvmlens-monitor`;
  manifest in `.claude-plugin/marketplace.json`). Update the skills when a user-facing flag or
  output changes.

Adding a command = a new `@Component @Command` registered in `JvmlensCommand.subcommands`;
keep analysis logic in the engine, not the command.

## Conventions

- **Engine stays dependency-free** (only `jdk.jfr.consumer`); CLI/MCP/agent are thin
  front-ends; every capture path produces JFR consumed by `Summarizer`.
- **Application-frame attribution**: lead with the first application frame. `Scope` skips the
  JDK, common frameworks and third-party libraries, test/mock/benchmark libraries, and native
  frames (`::`, `.so`); `-a/--app-package` is include-only, `-x/--exclude` adds prefixes and
  wins inside an include; the dominant app package is auto-detected and surfaced.
- **Trust signals are first-class** (user calls this essential): every ranked row shows its
  absolute hit `count`; sections are tagged `[sampled]` (statistical) vs `[measured]` (exact —
  locks/GC); a `⚠` caveat fires under 200 execution samples.
- **The heuristic under-interprets** — hedges, no confident "leak" from sparse old-object
  samples. Give the LLM clean data over a confident wrong label, and never print a figure
  jvmlens cannot measure (an honest "upper bound" beats an invented `removable ≈ X%`).
- **Diffs anchor on absolute weight and only annotate.** A row that moves against, or out of
  step with, its total gets a `possible sampling redistribution` hedge; never suppress or
  re-rank it, because a real localized regression looks identical. Fixed-duration captures
  conflate per-op cost with throughput → point at a fixed-iteration `bench` A/B or `--ops`.
- **No silent misconfiguration.** An unknown option hard-errors with a did-you-mean
  (`JvmlensProfiler`); a baseline from a different benchmark is skipped with a warning; a scope
  that hides the profile says so (`ScopeCoverage`). A wrong result that still looks like a
  result is the worst failure.
- **Row keys are `Type.method` on a stable type name.** Source lines, teasers and via frames
  are display-only, and per-run name parts are stripped at ingestion (`Teasers.stableName`), so
  diffs and gates match across runs.
- **Agent rules.** Every advice carries `suppress = Throwable.class` and each `*Store.record`
  is wrapped by `FailGuard` — the agent must degrade monitoring, never crash the host. A new
  dimension matches the client type by name (no compile dependency), is `OpStore`-backed, adds
  its flag via `OpStore.sections(…)` plus a `sectionRule` in `FixHints`, and hooks the lowest
  client, never the Spring template above it.
- **Build gates**: spring-javaformat + checkstyle + PMD run at the `validate` phase; the
  JaCoCo ≥80% line gate runs at `verify` (use `scripts/dev-verify.sh` or `mvn verify`, not a
  bare `package`). Run `spring-javaformat:apply` first. Transport/bootstrap classes (`Main`,
  `McpServerCommand`, agent, snapshot glue) are jacoco-excluded.
- **Tests** synthesize real recordings via `jdk.jfr.Recording`/attach at runtime — no committed
  `.jfr` fixtures. Each detector test pairs the firing case with a look-alike that must stay
  quiet (`test/.../harness`).
- **Dogfood loop**: profile real projects → file `field-finding` issues (`scripts/field-finding.sh`)
  → fix → revalidate. Methodology: small CPU/memory/wait workloads, not giant cold inputs.
- **Infra**: k3s is managed via kubectl/helm (ns `unitrack`); **Portainer is only the Docker
  host**; images live in the Zot registry `registry.example.com:5000` (pull secret `my-regcred`).
  Lab deploy specifics live in the private `jvmlens-deploy` overlay, and the leak denylist and
  baselines live in infra — never in this repo.

## Gotchas

- **PMD bans `synchronized`** (method and statement) → use `ReentrantLock`. It also bans
  `setAccessible`, and an increment as a switch-arrow body (`case X -> this.n++;` → keep the
  braces).
- **`Summarizer.java` sits at the 800-line checkstyle cap** (methods cap at 80 lines). Put new
  logic in a helper (`Teasers`, `ViaFrames`, `HarnessShare`, `ScopeCoverage`) and wire it in
  with one to three lines.
- **`central-publishing-maven-plugin` ignores `maven.deploy.skip`** (that flag only governs
  `maven-deploy-plugin`). To keep a module off Central use `<excludeArtifacts>` on the
  aggregator config; a per-module skip on the last reactor module can skip the whole upload.
  cli/agent/jmh are published.
- **A ByteBuddy by-name matcher only advises methods the matched type *declares*.** Lettuce
  declares `get`/`set` in a superclass, so matching the impl interface fired nothing; match the
  declaring type or a package prefix. Only a forked-JVM IT against the real driver catches this.
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
run `/evolving-claude-md:compact` to graduate stable lessons and move old entries to
`docs/decisions/`.

### Decisions & Learnings (Recent — last 14 days)

- 2026-10-07 — **safe-recording-names** — every string read from a recording passes `Teasers.safe` on the way in (type + method via `stableName`/`frameKey`, I/O host/address/path, pinned reason). **Allowlist**: printable ASCII minus the backtick, plus letters/digits of any script; everything else → `?`; cut at 240 chars on a code-point boundary. Why: names land in LLM-facing text, and two denylists (characters, then Unicode categories) each missed invisible characters — they hide in mark, symbol and space categories. Stops break-out and smuggling, not persuasion.

- 2026-10-07 — **summary-notes** — `ProfileSummary` gains `notes` (`withNotes`); the harness, test-run and scope-coverage notes render as `> ⚠` lines under the header and as a JSON `notes` array, no longer appended to `cause`. Why: `cause` is also written to the agent's `history=` file every interval, so the notes were polluting the trend data; and three sentences glued onto one line buried the cause. Additive JSON key; the 17-arg constructor stays as a back-compat overload.

- 2026-10-07 — **summarizer-headroom** — `Summarizer` 800 → 736 lines, behaviour-neutral: the pure suspected-cause heuristic moved to `Cause` (`Cause.suspected(Cause.Signals)`), and three identical `humanBytes` copies (Summarizer, Renderers, ProfileDiff) became one `Teasers.humanBytes`. Why: every change this week needed a helper moved out first to stay under the checkstyle cap.

- 2026-10-07 — **async-profiler-jdk25** — `ap-loader-all` 3.0-9 → 4.5-13. With async-profiler 3.0, `profile --engine async` **killed the target JVM** on JDK 25 (SIGSEGV in `Profiler::updateThreadName` at thread start). The test hid it: a failed attach is an `Assumptions.abort`, so it showed as skipped. The test now asserts the target is still alive before skipping. Why: a profiler must never take down the process it observes; a skip must not mean a crash.

- 2026-10-07 — **default-library-scope** — `Scope` default not-application list widened (Kotlin/Scala, Guava/Gson/Protobuf, gRPC, Jetty/Undertow/Quarkus/Micronaut/Vert.x, OkHttp, Mongo/Redis/MySQL/MariaDB/Oracle drivers, jOOQ, Caffeine, AspectJ, ASM, OGNL, FreeMarker, OTel); `org.xml`/`org.w3c`/`org.ietf` are RUNTIME. Paired with `ScopeCoverage`: when ≥50% of CPU samples have no app frame it names the package holding them + the `-a` to pass. Why: a wider list makes "scope hides everything → silent empty hot paths" likelier. `Summarizer` is at 800 lines again.

- 2026-10-07 — **test-run-detection** — `HarnessShare` now also notes `⚠ Recorded from a test run (<launcher>)`, read from `jdk.JVMInformation.javaArguments` (Surefire / Gradle test worker / IDE runner / console launcher / TestNG), frames only as fallback. Why: runner frames sit at the stack bottom, the first thing JFR's depth-64 limit cuts. `Scope` defaults gain `TEST_LIBRARIES` (JUnit, Mockito, ByteBuddy, AssertJ, JMH, … + `BenchCommand`) as never-application: a Mockito run named `org.mockito.internal…` the app hot path. `-a` still overrides.
- 2026-10-07 — **logging-json-hints** — two `--hints` rules: logging-backend frames (Logback/Log4j/JUL — not the SLF4J facade) → level / parameterised messages / async appender; Jackson *construction* frames (`ObjectMapper.<init>`, `*SerializerFactory`, `DeserializerCache._create`, 2.x + 3.x `tools.jackson`) → reuse one mapper. Ordinary `BeanSerializer.serialize` stays quiet (inherent work). **Rejected:** a "recorded from a JMH fork" note — it would add a line to every JMH summary, the tool's main use, and say nothing new.
- 2026-10-07 — **stable-generated-names** — `Teasers.stableName` also strips the per-run part of generated classes: JDK proxies (`jdk.proxy1.$Proxy0`, numbered by creation order), ByteBuddy subclasses (`$MockitoMock$…`, `$HibernateProxy$…`, `$ByteBuddy$…`, random suffix) and reflection accessors (counter). Same GONE + NEW diff split as #161. Each rule is anchored to the generator's shape so `com.acme.Proxy2` survives. Left alone: Spring 6 `$$SpringCGLIB$$0` (index-stable). Mockito + JDK proxy formats measured; Hibernate/ByteBuddy from their naming rules, not measured here.

- 2026-10-06 — **via-frame** — a hot-path teaser appends `· mostly via <frame> n/total`: the non-runtime frame below the app frame that owns ≥50% of the path (inclusive, counted once per sample), nearest the leaves (`ViaFrames`). Why: app entry + JDK leaf were both right but the lever was the library frame between (OGNL `getReadMethod`). One inclusive rule instead of a per-leaf "nearest caller" — it also names the lever of a `⚠ diffuse` path. New `--hints` rule: uncached reflective lookup → memoize. #162.
- 2026-10-06 — **harness-note** — the suspected cause appends `⚠ Looks test-harness dominated` when ≥20% of CPU samples (≥50 samples) or allocation has a mock-library frame anywhere in its stack (`HarnessShare`: Mockito/EasyMock/PowerMock/jMock/MockK). Mock libraries only: JUnit frames sit under every test sample, and ByteBuddy alone also serves Hibernate/agents in production. Appended to `cause` to avoid a `ProfileSummary` schema change. Also `bench -o <file>`. Gotcha: PMD bans `case X -> this.n++;` (AssignmentInOperand) — keep the braces. #163.
- 2026-10-06 — **gate-absolute-backed** — diffs analyze both sides un-truncated (`RankLimits.full`); `ProfileDiff` still lists each side's top-N but looks values up in the whole list, so NEW/GONE mean truly absent. `PerfGate` `*-pp` = min(share move, sample move as % of **baseline** total). Why: a top-N baseline made below-cutoff paths "NEW", and share of a shrunken total failed 3/3 improvement-only diffs. min, not baseline-only: a longer after-capture must not read as a regression. #165.
- 2026-10-06 — **stable-lambda-names** — row keys strip a hidden class's per-JVM identity at ingestion (`Teasers.stableName`/`frameKey`): `Foo$$Lambda.0x…` and `Foo$$Lambda$14/0x…` → `Foo$$Lambda`, for frames, allocated types and monitor classes. Why: the address differs every run, so one lambda diffed as a GONE + NEW pair and could trip `--assert new-hotpath-pp`. Done at ingestion, not in `ProfileDiff`, so summaries lose the noise too; lambdas of one class now share a row. #161.
- 2026-10-06 — **recorder-self-filter** — the engine drops any event whose stack holds a recorder-machinery frame (`jdk.jfr.internal.PlatformRecorder`/`EventInstrumentation`/`JVMUpcalls`; `Recordings.isRecorder`), silently, like the `file null` I/O sink. Why: on JDK 24+ `Recording.start()` retransforms event classes via the classfile API and one alloc sample carried GBs — it read as the workload generating bytecode. Narrow list on purpose: `jdk.jfr.internal.event` (app-triggered commits) stays. #160.
- 2026-10-06 — **bench-cp-isolation** — `bench --cp` now parents the workload `URLClassLoader` on the **platform** loader, not jvmlens's. Why: parent-first delegation to the Boot `LaunchedClassLoader` made any library jvmlens bundles (Spring 7, Jackson, SLF4J) shadow the workload's own — a Spring 6 app silently benchmarked on Spring 7 and lost its resource lookups. `--cp` must now be the workload's *full* classpath. No-`--cp` runs unchanged. #164.

- 2026-Q2 — **archived** — 37 entries → docs/decisions/2026-Q2.md.
- 2026-Q3 — **archived** — 20 entries → docs/decisions/2026-Q3.md.

### Historic

Earlier build-out entries (v0.1→v0.2: engine, outputs, profile/watch/mcp, scope, async,
agent, ci) graduated into Architecture / Conventions / Gotchas above. Full chronology:
`docs/decisions/2026-06-jvmlens-buildout.md` (and git log).
