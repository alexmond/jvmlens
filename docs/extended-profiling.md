# Extended profiling — beyond CPU/memory/wait (research + epics)

Today jvmlens summarizes the three *resource* dimensions a JVM exposes through JFR: CPU
(execution samples), memory (allocation + GC + old-objects), and wait (lock/blocked time).
This note researches the landscape for **application-semantic** dimensions — web endpoints,
database queries, messaging, caches, outbound calls — and proposes the epics to get there
without abandoning what makes jvmlens jvmlens (JFR-unified engine, dependency-free core,
LLM-ready ~400-token output, honest under-interpretation).

## 1. Competitive landscape (summary)

The full landscape — four camps, scored across the extended dimensions — is in
[`competitor-analysis.md`](competitor-analysis.md). The takeaways that shape this plan:

- **Continuous profilers** (async-profiler, Pyroscope, Datadog Profiler) are **blind to app
  semantics** — a method is hot or a thread blocked, not *which endpoint* or *which query*.
- **Desktop profilers** (**JProfiler**, **YourKit**) already have exactly the semantic "probes"
  we want — JDBC/JPA, HTTP/servlet, JMS, sockets — but behind a GUI, attach-based, commercial.
- **APM / tracing** (**OpenTelemetry**, Datadog, **New Relic**, Dynatrace) gets the semantics via
  bytecode spans — exactly the mechanism the jvmlens **agent already has** (relocated ByteBuddy) —
  but as a heavy collector→backend→dashboard pipeline built for humans.
- **JVM-embedded APM** (**Glowroot**) is the architectural twin: single-JVM bytecode capture with
  *SQL aggregation* — but an embedded UI, not a digest. Proof the capture is feasible self-contained.
- **JDK Mission Control** already turns JFR into human-readable findings via a **rules engine** — the
  human-facing analog of jvmlens's heuristic cause.

**The gap jvmlens fills:** the capability exists everywhere; the **form factor doesn't**. Nobody
distills these semantic dimensions to a few hundred ranked, source-attributed tokens a coding agent
can act on — with no pipeline, no GUI, no egress. "Endpoint `POST /orders` p99 5s, dominated by
query `SELECT … FROM line_item WHERE …` (N+1, 1.2k calls/req)" in the same compact report as the hot
path and GC. That's uncontested.

## 2. Signal-source strategy (three tiers by cost/fidelity)

How jvmlens acquires each new dimension, cheapest first:

**Tier 1 — JFR-native, zero instrumentation.** Already in the recording the engine reads:
- `jdk.SocketRead` / `jdk.SocketWrite` — outbound/inbound **network I/O** by remote
  `host:port`, bytes, and blocked time (default threshold ~20ms). Gives an "external I/O"
  dimension: which downstream (DB host, API, broker) dominates blocking I/O — *without*
  knowing it's SQL.
- `jdk.FileRead` / `jdk.FileWrite` — blocking file I/O.
- `jdk.VirtualThreadPinned` — **virtual-thread pinning** (the "silent performance killer" of
  the Loom era), with a `pinnedReason` in JDK 24+ (`MONITOR` vs `NATIVE_METHOD`), stack
  attributed. A measured, high-value modern signal, free from the same recording.

**Tier 2 — ByteBuddy app-level instrumentation (the agent's existing mechanism).** Advice on
well-known framework entry points → per-operation aggregates (or custom JFR events consumed
by the same engine):
- **web** — Servlet / Spring MVC handler / JAX-RS: endpoint, count, latency distribution, error rate.
- **db** — `java.sql.Statement` / `PreparedStatement.execute*`: **sanitized SQL shape**
  (literals parameterized), count, latency, rows; N+1 detection (same shape × many/request).
- **messaging** — Kafka / JMS / Rabbit producer+consumer: topic/queue, throughput, consumer lag, latency.
- **cache** — Jedis / Lettuce / Spring Cache: hit/miss, op latency.

This is Glowroot-grade semantic capture, but the output is the compact summary, not a UI.

**Tier 3 — consume existing observability.** If the app already runs OTel, Micrometer, or
Spring Boot Actuator, read those (OTLP/in-process SDK, meter registry, `/actuator/metrics`)
and summarize — no re-instrumentation, respects the shop's existing setup.

## 3. Proposed epics

### Epic E1 — JFR-native I/O & virtual-thread-pinning dimensions  *(Tier 1; smallest; ships first)*
Pure engine work, zero new instrumentation, reuses the dependency-free `jdk.jfr.consumer`.
- `Summarizer` aggregates `jdk.SocketRead/Write` (+ file I/O) → an **External I/O** section:
  top remote `host:port` by blocked time + bytes, app-attributed via stack.
- `jdk.VirtualThreadPinned` → a **VT pinning** section (top sites by pinned time, `pinnedReason`).
- New report focuses `io` and `pinning`; extend `ProfileSummary`, `Renderers`, MCP tools.
- Extend `History.Sample` (the long-run track) with io/pinning scalars + trend lines.
- **Why first:** immediate value (network-bound services, Loom adoption), no version-matrix
  maintenance, validates the "more dimensions" rendering before the expensive instrumentation work.

### Epic E2 — Semantic app instrumentation (web / db / messaging)  *(Tier 2; the headline)*
A pluggable instrumentation SPI in the agent (advice modules), aggregating per-operation,
rendered as new sections; this is what takes jvmlens "beyond cpu/mem/wait."
- **E2a web** — HTTP endpoint hotspots + latency + errors.
- **E2b db** — sanitized SQL shapes, latency, N+1 detection (the highest-value sub-epic; SQL
  is where most app latency hides and where an LLM diagnosis is most actionable).
- **E2c messaging** — producer/consumer throughput, latency, consumer lag.
- **E2d cache** — hit/miss + op latency.
- Cross-cutting within E2: SQL/PII sanitization (reuse the snapshot redaction direction),
  per-dimension opt-in config, sampling/thresholds for prod safety, and a **tight version
  scope** (instrument a short list of dominant libs; OTel/Glowroot maintain huge matrices —
  jvmlens must not). Emit as custom JFR events where possible so the one engine still consumes everything.

### Epic E3 — Consume existing observability  *(Tier 3; integration)*
Read OTel / Micrometer / Actuator if present and summarize the app dimensions without
re-instrumenting. Lower priority; serves shops already on OTel who want the LLM-ready digest.

### Epic E4 — Unified rendering, correlation & long-run coverage  *(cross-cutting)*
- Extend `Report` (`io`/`web`/`db`/`messaging`/`pinning`), `Renderers`, and MCP scoped tools
  for every new dimension; keep the `[sampled]`/`[measured]` honesty tags and per-row counts.
- **Correlation** — the APM superpower jvmlens can approximate via shared stacks: tie a slow
  endpoint to its hot path / dominant query / GC pressure ("`POST /orders` slow → query Y → N+1").
- Full `history`/`trend` coverage so the long-running monitor trends endpoints/queries over days.

## 4. Validation

Extend the planted-pathology method (`examples/Workload.java`) with web/db/messaging
scenarios with known answers (an N+1 endpoint, a slow query, a lagging consumer), then run
the same **blind A/B vs raw** that settled the core thesis — score whether the summary lets
a fresh LLM name the planted bug at fewer tokens than raw spans/logs.

## 5. Risks & non-goals

**Risks:** instrumentation overhead in prod (mitigate: sampling, thresholds, opt-in);
library version drift (mitigate: tight scope, degrade-and-detect like the snapshot `-g`
work); SQL/PII leakage (mitigate: sanitize before it reaches the summary).

**Explicit non-goals:** jvmlens is *not* becoming an APM. No distributed tracing across
services, no collector/backend/dashboards, no multi-language, no per-request span store. The
unit stays a **digest, not spans**; single-JVM; LLM-facing; local-only (no egress). When a
shop needs full distributed tracing, that's OTel's job — jvmlens summarizes one JVM for an agent.

## 6. Sequencing

E1 (engine-only, low risk) → E2b (db — highest value) → E2a (web) → E4 correlation →
E2c/E2d → E3. Each epic is independently shippable and independently validated.

---

### Sources
- JFR built-in socket/file I/O events — [DZone: Digging into sockets with JFR](https://dzone.com/articles/analyzing-tcp-socket-with-java-flight-recorder), [JEP 328](https://openjdk.org/jeps/328)
- `jdk.VirtualThreadPinned` + `pinnedReason` (JDK 24) — [Java 24 thread pinning revisited](https://mikemybytes.com/2025/04/09/java24-thread-pinning-revisited/), [JEP 491](https://openjdk.org/jeps/491), [Continuous pinning monitoring with Spring Boot + JFR](https://mikemybytes.com/2024/04/17/continuous-monitoring-of-pinned-threads-with-spring-boot-and-jfr/)
- OTel Java auto-instrumentation coverage (JDBC/HTTP/Kafka/JMS/Redis) — [OpenTelemetry supported libraries](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/)
- Continuous-profiler dimensions — [Grafana Pyroscope profile types](https://grafana.com/docs/pyroscope/latest/introduction/profiling-types/), [Datadog Continuous Profiler](https://docs.datadoghq.com/profiler/)
- JVM-focused APM prior art — [Glowroot](https://github.com/glowroot/glowroot), [Glowroot instrumentation](https://glowroot.org/instrumentation.html)
