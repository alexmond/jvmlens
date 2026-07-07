# Competitor analysis

How jvmlens compares to the JVM profiling / observability landscape, focused on the
**extended-profiling direction** (beyond CPU/memory/wait into web, db, messaging, I/O), and
which capabilities to adopt next. The epic plan that follows from this lives in
[`extended-profiling.md`](extended-profiling.md).

> Verified against official docs during research (2026). Fast-moving facts (commercial
> pricing, brand-new AI features, maintenance status) are flagged inline — re-verify before
> quoting externally.
>
> **Refreshed 2026-07 against the shipped agent surface.** Most of the extended-profiling plan
> has since landed: the agent now ships the semantic dimensions (`web`, `db` with sanitized
> SQL + N+1, `messaging`, `cache`), the JFR-native ones (external I/O, virtual-thread pinning),
> plus deadlock detection and hedged cross-dimension correlation. The old 🔜 cells below are
> now ✅ and re-tagged `[shipped]`. Still open: direct **OTel** consumption (only Micrometer-
> registry summarize ships) and **NoSQL** probes.

**jvmlens** (the subject): a dependency-free **engine** (`jdk.jfr.consumer` only) that reduces
a JFR recording to a **~400-token, LLM-ready** summary — ranked, application-attributed hot
paths / allocation sites / lock contention / GC, with a hedged heuristic cause and per-row
trust signals. Front-ends (CLI `analyze`/`profile`/`watch`/`trend`, an `-javaagent`, an MCP
server) all feed the same engine. Distinctive bets: **the summary is the product** (not a
flamegraph or a dashboard); **local-only, zero LLM egress**; **honest under-interpretation**;
**one JFR-unified engine**. It began with the three JFR *resource* dimensions; the agent now
also covers the *application-semantic* ones (web/db/messaging/cache) this doc originally mapped
the path to.

Legend: ✅ first-class · ✅†  shipped since first draft (was 🔜) · ⚠️ partial / add-on / indirect ·
❌ not offered · — N/A · 🔜 still planned (epic)

---

## 1. Capability comparison

Rows are the dimensions this track is about (the resource baseline, then the semantic/extended
signals), plus the axes where jvmlens differentiates. jvmlens's ✅† cells map to the (now largely
shipped) epics in [`extended-profiling.md`](extended-profiling.md).

### 1a. Continuous profilers (jvmlens's home camp)

| Dimension | **jvmlens** | async-profiler | Grafana Pyroscope | Datadog Profiler |
|---|---|---|---|---|
| CPU hot paths | ✅ | ✅ | ✅ | ✅ |
| Allocation / heap | ✅ | ✅ | ✅ | ✅ |
| Lock / wait | ✅ | ✅ | ✅ mutex/block | ✅ |
| Wall-clock | ⚠️ via JFR | ✅ | ✅ | ✅ |
| Socket / file I/O | ✅† (JFR events) | ⚠️ | ❌ | ⚠️ |
| Virtual-thread pinning | ✅† | ⚠️ | ❌ | ❌ |
| HTTP / JDBC / messaging **semantics** | ✅† (agent probes) | ❌ | ❌ (needs tracing) | ⚠️ via Datadog APM |
| Deadlock / cross-dim correlation | ✅† | ❌ | ❌ | ⚠️ |
| Heuristic/automated findings | ✅ (1-line cause) | ❌ | ❌ | ⚠️ |
| **LLM-ready compact output** | ✅ | ❌ flamegraph | ❌ time-series | ❌ UI |
| Local / no egress | ✅ | ✅ | ⚠️ self-host server | ❌ SaaS |
| Model | OSS (MIT) | OSS | OSS + cloud | proprietary SaaS |

### 1b. Desktop / commercial profilers (the "probes" camp — closest semantic prior art)

| Dimension | **jvmlens** | JProfiler | YourKit | JDK Mission Control | VisualVM |
|---|---|---|---|---|---|
| CPU / alloc / lock | ✅ | ✅ | ✅ | ✅ (JFR) | ✅ sampling |
| Socket / file I/O | ✅† | ✅ probe | ✅ probe | ✅ JFR events | ⚠️ |
| HTTP / web | ✅† | ✅ servlet/JSP/web-svc probe | ✅ Java EE | ❌ | ❌ |
| JDBC / SQL | ✅† (sanitized SQL + N+1) | ✅ JDBC probe (+ hot statements) | ✅ Databases probe | ❌ | ❌ |
| JPA/Hibernate / ORM | ⚠️ N+1 via JDBC (no ORM probe) | ✅ probe | ⚠️ | ❌ | ❌ |
| NoSQL (Mongo/Cassandra/HBase) | ❌ (later) | ✅ probes | ⚠️ | ❌ | ❌ |
| Messaging (JMS / Kafka) | ✅† | ✅ probe | ⚠️ | ❌ | ❌ |
| Cache (hit/miss + op latency) | ✅† | ⚠️ | ⚠️ | ❌ | ❌ |
| Automated analysis / rules | ✅ heuristic | ⚠️ | ⚠️ | ✅ **rules engine** | ❌ |
| Output unit | **digest (tokens)** | rich GUI | rich GUI | GUI + JFR rules | GUI |
| Production-safe / low overhead | ✅ JFR | ✅ | ✅ | ✅ JFR | ⚠️ dev-only |
| LLM-ready | ✅ | 🆕 *AI-agent add-on (16.1, 2026)* | ❌ | ❌ | ❌ |
| Model | OSS (MIT) | commercial | commercial | OSS (OpenJDK) | OSS |

### 1c. APM / distributed tracing (semantic gold standard — but heavy & human-facing)

| Dimension | **jvmlens** | OpenTelemetry (Java agent) | Datadog APM | New Relic | Dynatrace / AppDynamics | Elastic APM |
|---|---|---|---|---|---|---|
| HTTP / JDBC / messaging semantics | ✅† | ✅ auto-instrument | ✅ | ✅ | ✅ | ✅ |
| Sanitized SQL + timing | ✅† | ✅ | ✅ | ✅ | ✅ | ✅ |
| Distributed tracing (cross-service) | ❌ non-goal | ✅ | ✅ | ✅ | ✅ | ✅ |
| Code-level profiling | ✅ | ⚠️ (profiling sep.) | ✅ correlated | ✅ | ✅ | ⚠️ |
| Pipeline required (collector/backend) | ❌ none | ✅ heavy | ✅ SaaS | ✅ SaaS | ✅ | ✅ |
| LLM-ready compact output | ✅ | ❌ spans | ❌ | ❌ | ❌ | ❌ |
| Local / no egress | ✅ | ⚠️ self-host collector | ❌ | ❌ | ❌ | ⚠️ self-host |
| Model | OSS | OSS (CNCF) | SaaS | SaaS | SaaS | open-core |

### 1d. JVM-embedded APM (single-JVM, bytecode — the architectural twin of jvmlens's E2)

| Dimension | **jvmlens** | Glowroot | inspectIT Ocelot |
|---|---|---|---|
| Slow-transaction / endpoint capture | ✅† | ✅ | ✅ |
| SQL capture + aggregation | ✅† | ✅ | ⚠️ via OTel |
| Bytecode instrumentation | ✅ (agent ByteBuddy) | ✅ | ✅ |
| Single-JVM, low overhead, self-contained | ✅ | ✅ | ⚠️ + collector |
| Output unit | **digest (tokens)** | embedded UI | Grafana |
| LLM-ready | ✅ | ❌ | ❌ |
| Model | OSS (MIT) | OSS (Apache-2) | OSS |

**Cross-cutting finding.** The semantic dimensions jvmlens wants (web/db/messaging) are *solved*
— twice over: **APM/OTel** does them via bytecode spans through a collector→backend→dashboard
pipeline, and **desktop profilers (JProfiler/YourKit)** do them via "probes" in a GUI. **JMC**
already turns JFR into human-readable findings via a rules engine — the human-facing analog of
jvmlens's heuristic cause. What *nobody else* ships is the intersection jvmlens now occupies: those
semantics distilled to a few hundred ranked, source-attributed tokens an LLM/coding-agent can act
on, with **no pipeline, no GUI, no egress, and a JFR-unified engine**. As of the 2026-07 refresh
jvmlens has actually *moved into* that intersection — the `web`/`db`/`messaging`/`cache` dimensions
now emit as digest sections. The capability exists everywhere; the *form factor* (LLM-ready digest)
remains uncontested, and jvmlens is now the one tool sitting in both.

---

## 2. Per-tool notes

### Continuous profilers
- **async-profiler** — OSS sampling profiler (CPU/wall/alloc/lock, native frames), low overhead, emits flamegraphs/JFR. No app semantics. jvmlens already *consumes* it (`profile --engine async` via ap-loader), so it's an input, not a rival.
- **Grafana Pyroscope** — OSS + cloud continuous profiling; profile types CPU/alloc/heap/inuse/mutex/block/wall. Gets "which request" only by correlating with **tracing** (Tempo), not from the profile itself. Self-hosted server or Grafana Cloud. [profile types](https://grafana.com/docs/pyroscope/latest/introduction/profiling-types/)
- **Datadog Continuous Profiler** — code-level CPU/alloc/wall, **auto-linked to Datadog APM spans** (that's where the SQL/endpoint semantics come from). Proprietary SaaS, egress. [docs](https://docs.datadoghq.com/profiler/)

### Desktop / commercial profilers
- **JProfiler** (ej-technologies, commercial) — the richest "probe" set: **JDBC, JPA/Hibernate, NoSQL (MongoDB/Cassandra/HBase), JSP/Servlets, JMS, web services, JNDI, RMI, files, sockets, processes**, layered semantically over CPU views; tracks calls across JVMs (HTTP/RMI/gRPC). GUI, attach/offline. *Flag: v16.1 (Apr 2026) added AI-agent integration — the competitive signal that profilers are moving toward agents; jvmlens's edge is being LLM-native + open + JFR-unified, not a GUI add-on.* [probes](https://www.ej-technologies.com/resources/jprofiler/help/doc/main/probes.html)
- **YourKit** (commercial) — built-in probes (`Databases` = JDBC/SQL, JNDI, …), Java EE profiling, low overhead. GUI. Comparable to JProfiler; both are the semantic prior art jvmlens reframes as a digest. [built-in probes](https://www.yourkit.com/docs/java-profiler/latest/help/built-in-probes.jsp)
- **JDK Mission Control** (OpenJDK, OSS) — the closest *analysis* analog: a JFR-driven **automated-analysis rules engine** producing human-readable findings/recommendations (e.g. inverted GC parallelism via the JDK 20 GC-CPU-time event). Same JFR substrate as jvmlens; the difference is GUI-and-human vs token-and-LLM. No semantic web/db probes (JFR has no SQL events). [JMC](https://github.com/openjdk/jmc)
- **VisualVM** (OSS) — free basic sampling/monitoring GUI; no semantic probes, dev-time. Baseline, not a real competitor for this track.

### APM / tracing
- **OpenTelemetry Java agent** (OSS, CNCF) — the semantic gold standard: zero-code auto-instrumentation for **JDBC (sanitized SQL + timing), HTTP servers/clients, Kafka/JMS, Redis, HikariCP pools**, etc. But it's a *spans → collector → backend → dashboard* pipeline built for humans. jvmlens should **consume** OTel/Micrometer where present (epic E3) rather than recompete. [supported libraries](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/)
- **New Relic / Datadog APM / Dynatrace / AppDynamics** — commercial APM: full semantic + distributed tracing, integrated profiling, dashboards, SaaS (egress). Powerful and human-facing; the opposite of jvmlens's local compact digest. Relevant as the capability bar, not the form factor.
- **Elastic APM** — open-core APM in the Elastic stack; same pipeline shape.

### JVM-embedded APM
- **Glowroot** (OSS, Apache-2) — **the architectural twin** of jvmlens's E2: bytecode instrumentation, slow-transaction traces, **SQL capture + aggregation**, HTTP, JVM metrics, low overhead, single-JVM — but an embedded *UI*, not a digest. The proof that semantic capture is feasible self-contained in one JVM. [Glowroot](https://github.com/glowroot/glowroot)
- **inspectIT Ocelot** (OSS) — bytecode agent feeding OpenCensus/OTel, visualized in Grafana. Closer to the OTel pipeline model.

---

## 3. Best capabilities to adopt (→ epics) — status

Mapped to [`extended-profiling.md`](extended-profiling.md). Most of this section has **shipped**
since the first draft:

- **From JFR itself, free (→ E1): ✅ shipped.** Socket/file I/O aggregation and `jdk.VirtualThreadPinned` — both already in the recording, both absent from most profilers' default summaries. Now always-on dimensions.
- **From JProfiler/YourKit/Glowroot probes (→ E2): ✅ shipped.** HTTP-endpoint (`web`), **JDBC sanitized-SQL + N+1** (`db`), messaging (`messaging`, JMS/Kafka), and cache (`cache`) via the agent's ByteBuddy advice — rendered as ranked digest sections, not GUI tabs. E2b (SQL) was the highest-value and landed first, as planned.
- **From OTel/Micrometer (→ E3): ⚠️ partial.** The `micrometer` dimension summarizes an *existing* registry; **direct OTel span consumption is still open** — the remaining integration lever for shops already wired up.
- **From JMC's rules engine (→ E4): ✅ largely shipped.** Deadlock detection and hedged **cross-dimension correlation** now ship (slow endpoint → its query → its GC), kept **hedged and LLM-prep** (data over confident labels). Richer rule coverage can still grow.
- **Still open:** direct OTel consumption (E3) and **NoSQL** probes (Mongo/Cassandra/HBase).

---

## 4. Gaps & differentiation

**Where jvmlens wins (the moat is the form factor + delivery, not the raw signal):**
- **LLM-ready compact digest** — every competitor outputs flamegraphs (profilers), spans/dashboards (APM), or GUI probe tabs (JProfiler/YourKit). None emits a few-hundred-token, ranked, source-attributed summary for a coding agent. This is uncontested.
- **Local, zero egress, no pipeline** — no collector/backend/SaaS; the engine is dependency-free and the output never leaves the host. APM can't say this; cloud profilers can't either.
- **One JFR-unified engine across every front-end** — offline file, live pid, continuous watch, in-process agent, MCP, and the long-running `trend` all reduce to the same `ProfileSummary`.
- **Honest under-interpretation** — a hedged cause and per-row trust signals, designed to feed an LLM clean data, not to win a dashboard.

**Where jvmlens is behind (this track's targets) — mostly closed as of 2026-07.** The
application-semantic dimensions the first draft lacked — HTTP endpoints, SQL, messaging, caches —
**now ship** in the agent, alongside external I/O and vthread pinning. The competitive bet played
out as planned: acquire the semantics through the **cheapest faithful source** (JFR first, then
targeted ByteBuddy) and render them in the one form factor the field lacks. **Residual gaps:**
- **No ORM-specific probe** — N+1 is inferred from JDBC, not Hibernate/JPA instrumentation (JProfiler has a dedicated ORM probe).
- **No NoSQL** — Mongo/Cassandra/HBase/Redis semantics (JProfiler/YourKit have probes).
- **No direct OTel consumption** — only Micrometer-registry summarize; OTel spans (E3) remain the open integration lever.
- **No distributed tracing** — an explicit non-goal, not a gap.

**Threat to watch:** profilers adding AI/agent front-ends (JProfiler 16.1, 2026). jvmlens's
durable edge is being **LLM-native and open from the engine up** — not a GUI with an AI button.

**Epics:** see [`extended-profiling.md`](extended-profiling.md) — **E1 ✅** (JFR I/O + pinning),
**E2 ✅** (semantic web/db/messaging/cache via ByteBuddy), **E3 ⚠️** (Micrometer done; OTel span
consumption open), **E4 ✅** (correlation + deadlock + long-run `trend` coverage). The planned
sequence E1 → E2b → E2a → E4 → E2c/E2d → E3 held; only E3's OTel half and NoSQL remain.

---

## 5. Sources

- **Continuous profilers** — [async-profiler](https://github.com/async-profiler/async-profiler) · [Grafana Pyroscope profile types](https://grafana.com/docs/pyroscope/latest/introduction/profiling-types/) · [Datadog Continuous Profiler](https://docs.datadoghq.com/profiler/)
- **Desktop / commercial** — [JProfiler probes](https://www.ej-technologies.com/resources/jprofiler/help/doc/main/probes.html) · [JProfiler 16.1 AI agents (2026)](https://www.ej-technologies.com/blog/2026/04/jprofiler-16-1-ai-agents-can-now-profile-your-java-applications/) · [YourKit built-in probes](https://www.yourkit.com/docs/java-profiler/latest/help/built-in-probes.jsp) · [JDK Mission Control](https://github.com/openjdk/jmc) · [VisualVM](https://visualvm.github.io/)
- **APM / tracing** — [OpenTelemetry Java supported libraries](https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/) · [New Relic Java APM](https://docs.newrelic.com/docs/apm/agents/java-agent/) · [Datadog APM](https://docs.datadoghq.com/tracing/) · [Elastic APM](https://www.elastic.co/observability/application-performance-monitoring)
- **JVM-embedded APM** — [Glowroot](https://github.com/glowroot/glowroot) · [Glowroot instrumentation](https://glowroot.org/instrumentation.html) · [inspectIT Ocelot](https://www.inspectit.rocks/)
- **JFR substrate** — [JEP 328 Flight Recorder](https://openjdk.org/jeps/328) · [socket events (DZone)](https://dzone.com/articles/analyzing-tcp-socket-with-java-flight-recorder) · [`jdk.VirtualThreadPinned` / JDK 24](https://mikemybytes.com/2025/04/09/java24-thread-pinning-revisited/) · [continuous pinning monitoring w/ JFR](https://mikemybytes.com/2024/04/17/continuous-monitoring-of-pinned-threads-with-spring-boot-and-jfr/)

> **Caveats carried from research:** JProfiler's AI-agent feature (v16.1) and all commercial
> pricing/feature claims are fast-moving — re-verify before external use. Profiler "probe"
> coverage varies by version; the OTel supported-library matrix changes per release.
