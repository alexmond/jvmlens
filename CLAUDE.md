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

The deliberate split (kept this way so the planned MCP server reuses the engine):

- **`Summarizer`** — the engine, and the only place real work happens. **Dependency-free
  by design**: uses only `jdk.jfr.consumer`, no Spring/picocli. `analyze(Path)` reduces a
  recording (via the inner `Aggregates`) to a render-agnostic **`ProfileSummary`** record;
  `Renderers` turns that into markdown/JSON/prompt. Keep both free of framework deps so the
  planned MCP server can serve the same `ProfileSummary`. JSON is hand-rolled for that reason.
- **`Main`** — Spring Boot entrypoint; wires picocli into Spring (`IFactory`) and bridges
  picocli's exit code to Spring's `ExitCodeGenerator`.
- **`JvmlensCommand`** — picocli root command (`jvmlens`), prints usage; holds subcommands.
- **`AnalyzeCommand`** — the `analyze <file.jfr>` subcommand; validates the file, calls
  `Summarizer.summarize`, prints to stdout. Returns exit 2 on unreadable file.

Adding a command = new `@Component @Command` class registered in `JvmlensCommand`'s
`subcommands`; keep analysis logic in `Summarizer`, not the command.

### Summarizer design decisions (don't undo without reason)

- **Frame attribution leads with the first *application* frame**, skipping runtime
  prefixes (`java.`/`jdk.`/`sun.`/`com.sun.`/`javax.`/`jakarta.` — the `RUNTIME` array
  via `frame(st, skipRuntime=true)`). This was the highest-value fix: naive leaf
  aggregation buried the user's hot method under JDK internals. A secondary self-time
  leaf view (`skipRuntime=false`) is kept intentionally.
- **The heuristic deliberately under-interprets.** It hedges and leans on
  GC-pause + allocation-site signal rather than over-claiming a "leak" from sparse
  `oldobject` samples. Prefer giving the LLM clean data over a confident wrong label.

### Tests

Tests synthesize real JFR recordings at runtime via `jdk.jfr.Recording` (run a workload,
dump to a temp file, summarize, delete) — there are **no committed `.jfr` fixtures**.

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

- 2026-06-22 — **goal** — jvmlens turns a JFR recording into a ~400-token LLM-ready markdown summary; the summarizer is the product, capture/parsing are JDK-provided. See → README.md.
- 2026-06-22 — **build** — `mvn package` fails on style/coverage, not just compile: javaformat+checkstyle+PMD at validate, JaCoCo ≥80% line gate. Why: run `spring-javaformat:apply` before building.
- 2026-06-22 — **architecture** — `Summarizer` is dependency-free (only jdk.jfr.consumer), kept framework-free so the planned MCP front-end reuses it. Why: one engine, thin CLI/MCP front-ends.
- 2026-06-22 — **attribution** — hot-path ranking leads with the first application frame, skipping java./jdk./sun./etc. Why: naive leaf aggregation buried the user's hot method under JDK internals.
- 2026-06-23 — **output** — `analyze -f md|json|prompt` renders one `ProfileSummary` three ways; JSON is hand-rolled. Why: scoped JSON is what the future MCP server serves, and the engine must stay dependency-free.
- 2026-06-23 — **cli** — picocli enum options are case-sensitive by default; set `setCaseInsensitiveEnumValuesAllowed(true)` so `-f json` works. Why: users type lowercase, not `JSON`.
- 2026-06-23 — **capture** — `profile <pid>` attaches via public `jdk.attach` + `jdk.management.jfr` MXBean (no jcmd, no --add-exports). Why: keeps the headless single-binary positioning; verified from classpath.
- 2026-06-23 — **dogfood** — real-project profiling feeds jvmlens gaps back as GitHub `field-finding` issues. Why: tuning is driven by field evidence, not guesses. See → `scripts/field-finding.sh`, issues #1/#2.
- 2026-06-23 — **attribution** — field finding: framework pkgs (spring, bouncycastle) pass the runtime filter and bury user code; needs configurable app-package scoping. See → issue #1.
- 2026-06-23 — **scope** — `Scope` defines application code: default skips JDK **+** common frameworks; `-a/--app-package` is include-only mode, `-x/--exclude` adds prefixes. Why: fixes #1 — validated on jhelm (default now leads with `HelmJavaApplication.main`, `-a org.alexmond` shows only the user's code). Auto-detect deferred.
- 2026-06-23 — **adequacy** — markdown emits a `⚠` caveat below 200 exec samples; `profile` gained `-w/--warmup` to skip startup. Why: fixes #2 — short cold runs profile classloading, not the workload; the caveat stops the LLM trusting noisy shares.

### Historic (older than 14 days · see git log for the build-up)

- (none yet)
