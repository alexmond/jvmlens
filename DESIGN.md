# jvmlens — design notes

Forward-looking architecture and decisions, carried over from the incubator so
work can continue here uninterrupted. This is the "why", not the "what" — for
usage see the README, for the plan see [ROADMAP.md](ROADMAP.md).

## Thesis

jvmlens is **"the agent that turns JVM runtime evidence into an LLM-ready
diagnosis."** The through-line is *runtime evidence → LLM*, across two
capabilities:

- **Profiling** (today) answers *performance* bugs — where is time / memory going.
- **Variable snapshots** (v2) answer *correctness* bugs — what was this value, here.

The moat is the **summarizer**, not the collection: a multi-MB JFR dump becomes
~400 tokens of ranked, source-attributed signal. Collection leans on existing,
proven tools (JFR, later async-profiler) — don't reinvent the sampler.

## Architecture: one engine, two front-ends

- **Engine** — `Summarizer` (dependency-free, `jdk.jfr.consumer` only). The core.
- **CLI** (today) — picocli `jvmlens analyze <file.jfr>`.
- **MCP server** (next) — the *same* engine exposed over MCP.

Build the engine once; CLI and MCP are thin front-ends. This also settles the
old "library vs CLI?" question — it's *library core + CLI + MCP*.

## Key decisions (learned this cycle)

1. **Attribution: lead with the first *application* frame, not the deepest JDK
   leaf.** Naive leaf aggregation buried the user's hot method under
   `VarHandle.index` / `Integer.formatUnsignedInt`. `Summarizer.frame(.., skipRuntime=true)`
   skips `java./jdk./sun./com.sun./javax./jakarta.`. This was the single
   highest-value fix. Keep a secondary "self-time leaf" view — it's still useful.
2. **Don't over-interpret.** A naive heuristic mislabeled a CPU hog as a "memory
   leak" off two stray old-object samples. Lean on GC-pause + allocation-site
   signal and hedge. Better: give the LLM clean data and let it reason.
3. **MCP is a first-class surface, not an afterthought** — and the win is
   *scoped, navigable tools* (progressive disclosure: overview → drill into the
   hot method → pull its allocation sites), NOT one `get_profile` blob. That's
   where the summarizer-as-moat pays off and avoids context overflow.
4. **Capture modes:** timed run + offline `analyze` (today); **continuous
   recording + dump-on-trigger** is the headline production mode (JFR ring
   buffer → `JFR.dump` on a latency/error/OOM trigger).
5. **JFR vs async-profiler:** JFR is the prod-safe default (built for ~1%
   always-on); async-profiler is a higher-fidelity opt-in (via ap-loader) where
   the environment allows (`perf_event_paranoid`, container caps).

## Prod vs dev

- **Profiling runs in prod** — that's what JFR is for. Keep to the low-overhead
  default profile; alloc + `oldobject` events cost more, budget them.
- **Attach model:** in prod, usually baked in at launch
  (`-XX:StartFlightRecording`) rather than dynamic attach (often disabled /
  namespaced in containers).
- **LLM egress is the real prod constraint.** Emitting profile data — let alone
  variable values — to a third-party LLM API is often disallowed. So:
  **emit the artifact locally, let the user/agent choose to send it**, and
  support local/self-hosted models. The MCP server *serves structured data; it
  does not call an LLM itself* — the model is whatever agent the user runs, so
  data stays local and egress is the user's choice. This is a differentiator:
  the SaaS incumbents require shipping your runtime data to their backend.

## v2 — variable-snapshot capture (parked; separate validation)

Extend the JVM agent with a **second capture mode**: non-breaking variable
snapshots (value at a line, optionally conditional, without stopping the app) →
through the same summarizer. One agent, two capture modes, one output layer.

- **Reuses** the agent host + output + summarizer. **New work:** a bytecode-
  instrumentation (ByteBuddy/ASM) or JVMTI path — the sampling engine cannot see
  variable values.
- **Headless gotcha:** locals need `LocalVariableTable` (compiled with `-g`),
  often stripped in release builds. Detect-and-degrade: fields always, args
  usually, locals only with debug info — tell the LLM what was stripped.
- **Frame it as snapshot capture, not interactive debugging** — interactive
  step/inspect fights the headless/unattended positioning.
- **Sequence after** the profiler MVP; it's a *separate* validation (different
  pain, users, competitor). Prior art: Lightrun / Datadog Dynamic
  Instrumentation / Rookout (all commercial SaaS/GUI).

## Competitive wedge

**OSS + backend-free + headless single-binary + LLM-native.** Every incumbent is
commercial SaaS or GUI. Lead OSS/CLI rival: **Argus** (`rlaope/Argus`) —
observability-shaped, not LLM-summarization-shaped. JProfiler MCP / JVM CodeLens
own the AI-summary thesis but are commercial/GUI; Lightrun/Datadog-DI own
variable-snapshot-for-AI but are SaaS. The honest claim is not "nobody does
this" — it's "nobody does this open-source and without a control plane."
