# MCP debug agent — design note (for review)

**Status:** proposal / not started. Captured from a design discussion; nothing here is built
yet. Decision recorded: **build a separate module, transport-and-target *switchable*** so the
same tool surface runs beside the target JVM now and inside it later.

## 1. What this is

Today `jvmlens mcp` exposes a read-only, navigable profile surface over MCP
(`McpServerCommand` + `ProfileTools` in `jvmlens-cli`): `overview` → drill into
`hot_paths` / `hot_leaves` / `allocations` / `lock_contention` / `io` / `pinning` /
`deadlock`, plus a live `profile <pid>` tool. It serves structured data only — **it never
calls an LLM**.

This note proposes turning that into a **debug agent**: a dedicated module whose MCP tools let
a coding agent *drive* diagnosis — not just read a summary, but capture variable snapshots,
arm triggers, dump on demand, and diff before/after — against a JVM that may be **local or
remote**. It is *diagnostic* debugging (why is it slow / stuck / leaking, and what values flow
through method X), **not** JDWP breakpoint-stepping. That stays a non-goal.

## 2. Mental model (the flip that matters)

**jvmlens is the MCP _server_; the LLM/coding agent is the _client_.** jvmlens never calls a
model — it exposes scoped tools and the agent decides what to pull. "A debug agent" = the
client driving those tools. Everything below preserves that: the recording (and any sensitive
data in it) stays on the host; only the compact summary crosses the wire.

## 3. Topologies

The tool surface is identical across all three; only the **transport** and the **source of
data / control** differ.

### 3a. Local — server as a child process (stdio)
```
Claude / MCP client ──stdio──▶ jvmlens mcp
                                 ├─ profile <pid> ─▶ live local JVM (attach + JFR)
                                 └─ overview/hot_paths/… ─▶ a .jfr on disk
```

### 3b. Remote — same server, tunnelled (no ports, no egress)
```
your box                       remote host / pod
Claude (MCP client)            target JVM
  launch = ssh host jvmlens mcp ──stdio──▶ jvmlens mcp ─▶ target (profile <pid>, analyze)
        only the ~400-token summary crosses the wire; the .jfr never leaves the host
```
`ssh host java -jar jvmlens.jar mcp` — the client command *is* the ssh (or `kubectl exec -i`,
`docker exec -i`) invocation; stdio tunnels over it. This is the property that beats an APM
pipeline for prod: no HTTP server, no open port, no collector, nothing leaves the host but the
digest.

### 3c. Remote headless — driving the in-process agent
For a container you can't attach to, the in-process `-javaagent:jvmlens-agent.jar` already
writes summaries to a file, watches a control file, and (new) emits **breach-only** via
`on-gc-ms`/`on-cpu-pct`/`on-old-objects` triggers. The debug agent reads those files and
writes the control file to drive it — no attach needed.

## 4. Decision — separate module, *switchable* (not folded into cli)

Folding this into `jvmlens-cli` would drag the MCP SDK + a server loop into the Spring Boot fat
jar — the module that already carries the "don't co-locate shade here, it double-packed Boot 4"
scar. So: a **separate module**.

The real fork was **where the debug agent runs**:

| | A — In-JVM (embed MCP in an agent) | B — Beside-JVM (`jvmlens-mcp` process) |
|---|---|---|
| Artifact | one `-javaagent` that *is* the server | external process (ssh/kubectl exec) |
| Transport | needs a **port** (MCP-over-HTTP) | stdio-over-ssh (no port) |
| Drives target via | direct `AgentControl` | the agent's **control file** |
| Cost | SDK + server thread inside prod JVM — blast radius vs the agent's fail-open rule | two moving parts; control acts through the file channel |
| Egress | opens a port | nothing leaves the host but the digest |

**Chosen: both, switchable** — build **B first**, design a seam so **A** is a later opt-in with
the *same tool surface*. B keeps the agent lean and fail-open and preserves no-egress; A is the
"single attach" dream but forces MCP-over-HTTP and a server thread in the host JVM, so it must
never be the default.

## 5. Module shape — `jvmlens-mcp`

```
jvmlens-mcp/                     depends on: jvmlens-engine + MCP SDK + jvmlens-capture
  DebugServer      bootstrap: register tools, pick a Transport, bind a DebugTarget
  Transport (iface)
    ├─ StdioTransport    phase 1 — external process, stdio-over-ssh
    └─ HttpTransport     phase 2 — in-JVM / sidecar, MCP-over-HTTP
  DebugTarget (iface)    the switchable seam
    ├─ FileTarget        beside-JVM: read the agent's summary/history file; write its control file
    ├─ LiveTarget        beside-JVM: profile <pid> on demand (LiveCapture)
    └─ InProcessTarget   in-JVM: direct AgentControl + in-memory summary (phase 2)
  tools/           overview/hot_paths/… (moved from cli) + snapshot/control/trigger/diff/trend
```

### The seam that delivers "switchable"
Every tool binds to an abstract **`DebugTarget`**, never to a concrete file or pid. A target
answers two things:

```
interface DebugTarget {
    ProfileSummary latest();        // from a .jfr, a live capture, or the agent's written summary
    String control(String command); // beside-JVM: write control file + read <file>.status back
                                     // in-JVM:    AgentControl.apply(command) directly
}
```

Only the target's *implementation* differs by topology; `ProfileTools` and the new debug tools
are identical either way. Same for `Transport` (stdio vs HTTP). That is the whole trick — one
tool surface, two bindings.

### Why phase 1 is cheap
The control channel already **is** the beside-JVM driving mechanism: `FileTarget.control()` is
exactly what `jvmlens control <file> <cmd>` does today (`AgentControl` + `ControlChannel` +
the `.status` readback). So the MCP `snapshot` / `trigger` / `control` / `dump` tools are thin
proxies over the mechanism just extended with dump-on-trigger — almost no new agent code.

## 6. Tool surface

Existing (read-only, keep): `overview`, `hot_paths`, `hot_leaves`, `allocations`,
`lock_contention`, `io`, `pinning`, `deadlock`, `profile`.

New (the "debug" verbs — proxy to `DebugTarget.control()` / `.latest()`):

| Tool | Wraps | Debug use |
|---|---|---|
| `snapshot` | agent `snapshot=Class#method` | capture argument/variable values at a suspect method, read back the digest |
| `control` | `AgentControl` | enable a dimension / dump now / adjust scope live |
| `trigger` | dump-on-breach (`on-*` / `trigger` cmd) | "capture only when it breaks" |
| `diff` | `ProfileDiff` (+ `--ops`) | "did my fix work" across two captures |
| `trend` | `History.digest` | long-run remote monitor: what changed over days |

## 7. The one real refactor

`LiveCapture` (attach + JFR MXBean) lives in `jvmlens-cli` today, but `jvmlens-mcp` needs it
for `LiveTarget`, and cli's own `profile`/`watch` also use it. Extract it into a small
**`jvmlens-capture`** module both depend on. This keeps the engine's "only `jdk.jfr.consumer`"
rule intact — `LiveCapture` pulls `jdk.attach`, which doesn't belong in the engine.

## 8. Phasing

- **Phase 1** — `jvmlens-mcp` + `StdioTransport` + `FileTarget`/`LiveTarget`; new tools
  snapshot/control/trigger/diff/trend. Ships as its own runnable jar; cli keeps `mcp` as a thin
  delegate (or drops it). Covers local **and** remote (ssh/kubectl) with the lean agent
  unchanged. Also does the `jvmlens-capture` extraction.
- **Phase 2** — `HttpTransport` + `InProcessTarget`, shipped as a **separate optional**
  `jvmlens-agent-mcp` shaded jar so the default agent stays SDK-free and fail-open. Same tool
  surface, bound to a live `AgentControl`. This is where the roadmap's "agent embedding the MCP
  endpoint" + "MCP-over-HTTP for sidecars" items land.

## 9. Invariants & non-goals (must survive both phases)

- **jvmlens never calls a model.** The server serves data; the LLM is the client.
- **The default `-javaagent` never gains a server thread or the MCP SDK.** The in-JVM transport
  is always an opt-in *separate* jar; the lean agent's fail-open guarantee is non-negotiable.
- **No egress by default.** Beside-JVM is stdio-over-ssh; only the digest leaves the host.
- **Not a step-debugger.** No JDWP, no breakpoints, no distributed tracing. Single-JVM,
  digest-not-spans, LLM-facing — same non-goals as the extended-profiling track.

## 10. Open questions for review

1. **`jvmlens-capture` scope** — just `LiveCapture`, or also the `watch`/`bench` capture glue?
   Smallest useful extraction vs one coherent capture module.
2. **Does cli keep an `mcp` subcommand** (thin delegate to the new module) or drop it entirely
   and point users at `jvmlens-mcp.jar`? Affects the CLI's surface and the docs.
3. **`snapshot` tool safety** — snapshot values are currently unredacted (PII redaction is a
   still-open v2 item). Gating `snapshot` behind an explicit opt-in flag, or blocking it until
   redaction lands, is a prod-safety call.
4. **Phase-2 HTTP transport auth** — an in-JVM MCP-over-HTTP endpoint opens a port; what's the
   minimum viable auth/bind story (localhost-only + ssh-forward vs a token)?
5. **Central publish** — `jvmlens-mcp` is a runnable tool jar like cli/agent; ship as a GitHub
   release asset (excluded from Central), consistent with the release-scope decision.

## 11. Grounding (current code)

- MCP server + tool wiring: `jvmlens-cli/.../McpServerCommand.java`, `.../ProfileTools.java`
  (stdio-only; remote = `ssh host jvmlens mcp`).
- Control channel to reuse: `jvmlens-agent/.../agent/AgentControl.java` (+ `ControlChannel`),
  `jvmlens control` CLI; dump-on-trigger via `on-*` launch args / `trigger` commands.
- Live capture to extract: `LiveCapture` in `jvmlens-cli` (used by `profile`/`watch`/mcp).
- Diff/trend to expose: `ProfileDiff` (now with `--ops` per-op), `History.digest`.
- Related open roadmap items this subsumes: "agent embedding the MCP endpoint" and
  "MCP-over-HTTP for multi-client/long-lived sidecars".
