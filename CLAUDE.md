# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

jvmlens reads a JFR (`.jfr`) recording and emits a compact, **LLM-ready** markdown
summary — a multi-MB `jfr print` dump (~hundreds of K tokens) becomes ~400 tokens
of ranked, source-attributed signal (hot paths, allocation sites, lock contention,
GC pressure, a heuristic cause). The summarizer is the product; capture and parsing
are JDK-provided (`jdk.jfr`). See `DESIGN.md` (the "why") and `ROADMAP.md` (the plan).

## Build & test

Use the Maven wrapper (`./mvnw`); `mvn` works if installed. Java 17.

```bash
./mvnw -q clean package                       # full build (runs all gates below)
./mvnw -q test                                # tests only
./mvnw -q test -Dtest=SummarizerTest          # single test class
./mvnw -q test -Dtest=SummarizerTest#summarizesCpuHotPath   # single method
./mvnw spring-javaformat:apply                # auto-fix formatting before committing
```

The build is **strict and will fail** on style/coverage, not just compile errors —
these all run as part of `package`:

- **spring-javaformat** + **checkstyle** + **PMD** run at the `validate` phase (before
  compile). Run `spring-javaformat:apply` to fix formatting; checkstyle/PMD config is
  in `checkstyle.xml`, `checkstyle-suppressions.xml`, `pmd-ruleset.xml`. Note
  spring-javaformat uses **tabs** for indentation.
- **JaCoCo** enforces ≥80% line coverage on the bundle (only `Main` is excluded). New
  logic generally needs a test or the build fails at `verify`/`package`.

## Run

```bash
java -jar target/jvmlens.jar analyze recording.jfr           # built jar
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="analyze,recording.jfr"   # dev
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
- **Build gates**: `mvn package` runs spring-javaformat + checkstyle + PMD (validate phase) and
  JaCoCo ≥80% line — run `spring-javaformat:apply` first. Transport/bootstrap classes (`Main`,
  `McpServerCommand`, agent, snapshot glue) are jacoco-excluded.
- **Tests** synthesize real recordings via `jdk.jfr.Recording`/attach at runtime — no committed
  `.jfr` fixtures.
- **Dogfood loop**: profile real projects → file `field-finding` issues (`scripts/field-finding.sh`)
  → fix → revalidate. Methodology: small CPU/memory/wait workloads, not giant cold inputs.
- **Infra**: k3s is managed via kubectl/helm (ns `unitrack`); **Portainer is only the Docker
  host**; images live in the Zot registry `nas1.home.int:5000` (pull secret `zot-regcred`).

## Gotchas

- **PMD bans `synchronized`** (method and statement) → use `ReentrantLock`.
- **picocli enums are case-sensitive** → `setCaseInsensitiveEnumValuesAllowed(true)` (Main sets
  it; standalone-CommandLine tests must too).
- **Agent dump on exit**: use JFR `setDumpOnExit`, not a shutdown hook (the hook races JFR's
  own teardown).
- **Self-attach** (ByteBuddyAgent / async-profiler into a child) needs
  `-Djdk.attach.allowAttachSelf=true`; CI may still block agent load → guard such tests with
  JUnit `Assumptions.abort`.
- **Agent jar packaging**: maven-shade must run *before* Spring Boot repackage; relocate
  `net.bytebuddy`; shade leaves inert `META-INF/versions/9/net/bytebuddy` (harmless).
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
- 2026-06-25 — **java-version** — tests compile at **Java 17 source** (runtime is 21), so Java 21 APIs (`Thread.ofVirtual()`) don't compile in tests — can't generate `jdk.VirtualThreadPinned` live; cover pinning via rendering/history tests, file-I/O for the io path.
- 2026-06-25 — **e2b-db** — agent `db` option instruments `java.sql.Statement.execute*` (ByteBuddy) → `SqlStore` aggregates by **sanitized** SQL shape (no literals/PII), rendered as a `db` Section via `ProfileSummary.withSections`. SQL from arg or `toString()` (driver-dependent; degrades to `?`). `SqlAdvice`/`SqlCapture` jacoco-excluded + shade-included like snapshot glue; `SqlSanitizer`/`SqlStore` unit-tested. [#28]
- 2026-06-25 — **e2a-web** — agent `web` option instruments `HttpServlet.service` (ByteBuddy, by-name jakarta+javax, request/response read **reflectively** so no servlet dep) → `WebStore` aggregates by `METHOD route-shape` (`WebSanitizer` ids→`{}`, query dropped) with error counts. Advice `catch (Exception)` not `Throwable` (PMD). Pattern for E2c/E2d: new package + shade-include + jacoco-exclude the advice/capture; agent merges all stores via `instrumentationSections()`. [#28]
- 2026-06-25 — **e4** — `Renderers.appendCorrelation` adds a hedged `Cross-dimension correlation` block (FULL only, ≥2 dims) co-locating top endpoint/query/IO/hot/lock/GC — co-occurrence not proof (no per-request trace). Report focuses `db`/`web` added; `History.Sample` gains `dbMs`/`webMs` + an `Application (web/db)` trend block. [#29]

### Historic

Earlier build-out entries (v0.1→v0.2: engine, outputs, profile/watch/mcp, scope, async,
agent, ci) graduated into Architecture / Conventions / Gotchas above. Full chronology:
`docs/decisions/2026-06-jvmlens-buildout.md` (and git log).
