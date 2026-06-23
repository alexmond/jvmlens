# jvmlens — roadmap

Staged plan with rough effort (focused solo; less with an AI coding agent on the
mechanical parts). Effort is concentrated in the **summarizer** — it's tuning-
bound, judged against LLM-diagnosis quality, and is the only genuinely hard part.
You are *not* building a profiler — capture (`jdk.jfr.Recording`) and parsing
(`jdk.jfr.consumer`) are JDK-provided.

## Now — done (v0.1)

- `analyze <file.jfr>` → LLM-ready markdown: application-attributed hot paths,
  allocation sites, lock contention, GC pressure, heuristic cause.
- Planted-pathology test workload + criterion-#1 methodology (see
  `examples/`).

## Next — finish the dev MVP (~weeks)

- [x] Scoped JSON + LLM-prompt outputs alongside the markdown — shipped as
      `analyze -f md|json|prompt` over one `ProfileSummary` (dependency-free, JSON
      hand-rolled). Sets up the MCP server's structured surface.
- [x] `profile <pid>` — live attach + timed JFR capture via the public
      FlightRecorder MXBean over a local management agent (no `jcmd`, no
      `--add-exports`). `-d/--duration`, `-s/--settings`, `-f/--format`, `-k/--keep`.
- [ ] Continuous recording + **dump-on-trigger** (ring buffer → `JFR.dump`).
- [ ] Tune the summarizer against real workloads — now **driven by field-finding
      issues** from dogfooding (see `examples/experiments.md` + `scripts/field-finding.sh`).
      First inputs from jhelm: configurable application-package scoping
      (#1 — **done**, `Scope` + `-a/--app-package`/`-x/--exclude`) and
      sample-count adequacy / steady-state capture (#2 — **done**, `⚠` low-sample
      caveat + `profile --warmup`). Success metric is still
      *cheaper* (≥3× fewer tokens at equal accuracy) or *more accurate* vs raw JFR.

## Then — reach + fidelity (~weeks)

- [ ] **MCP server** — same engine, *scoped/navigable* tools (overview →
      drill → allocation sites), not a blob. ~1 week once the engine is stable.
- [ ] async-profiler fidelity via ap-loader, behind the same interface.
- [ ] Emit-local / user-chosen-model so egress-restricted (prod) shops can use it.

## Later — v2 (separate validation; see DESIGN.md)

- [ ] Variable-snapshot capture (ByteBuddy/JVMTI), non-breaking, condition-gated,
      with debug-info detect-and-degrade and (for prod) PII redaction.

## Explicitly deferred

- GraalVM native single-binary (native build was dropped for now).
- Maven Central publish (wired in the `release` profile; needs a verified
  `org.alexmond` namespace + secrets; repo is private for now).
- Memory/leak analysis depth (`oldobject`) — sparse signal in short runs; the
  GC-pause + alloc-site combo is the more reliable leak signal. Don't let it
  eat weeks.

## Immediate next step

Tune + extend the summarizer on a *real* project, and add `profile <pid>` live
capture so it works without a pre-recorded `.jfr`. That's the shortest path from
"works on a fixture" to "saves me tokens on my own work."
