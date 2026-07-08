---
name: jvmlens-monitor
description: Drop jvmlens into the CURRENT JVM project to profile it — turn a JFR recording into a ~400-token, source-attributed, LLM-ready summary instead of a multi-MB dump. Headline mode is the long-running monitor: attach the in-process agent with history= so it appends one compact CPU/memory/wait sample per interval, let it run for days, then `jvmlens trend` reduces the run to a change-over-time digest (rising/flat/falling per dimension, hot-path shift, lock-contention emergence, a hedged retention/leak indicator). Also covers one-shot analyze, live profile <pid>, an MCP server for this session, and a k8s sidecar. Use when the user says "profile this project", "drop in jvmlens", "set up jvmlens", "add the jvmlens agent", "long-running perf monitor", "monitor cpu/memory/wait over days", "trend over time", "let it run a few days then check", or "profile <thisproject> with jvmlens". jvmlens lives at github.com/alexmond/jvmlens; it never calls an LLM and never ships recordings anywhere.
---

# Profile the current project with jvmlens

Sets up jvmlens against **the project this session is in**. jvmlens reads JFR (the JDK
already produces it) and emits a few hundred tokens of ranked, application-attributed
signal. The canonical reference is `INTEGRATING.md` in github.com/alexmond/jvmlens — this
skill is the actionable subset, with the **long-running monitor** as the default.

> Substitute the current project for `myapp` / `com.example.myapp` throughout.
> jvmlens runs locally only — no egress.

## 0. Prerequisite — get the jars (once)

jvmlens is **on Maven Central** (`org.alexmond`: `jvmlens-cli` is the executable CLI + MCP +
trend jar; `jvmlens-agent` is the `-javaagent`). Pull a pinned release, or grab the rolling
`latest` pre-release, or build from source (Java 17+). You need both jars.

```bash
# (a) Maven Central — pinned release (set VER to the latest release)
VER=0.3.0; mkdir -p tools
curl -fsSL "https://repo1.maven.org/maven2/org/alexmond/jvmlens-cli/$VER/jvmlens-cli-$VER.jar"     -o tools/jvmlens.jar
curl -fsSL "https://repo1.maven.org/maven2/org/alexmond/jvmlens-agent/$VER/jvmlens-agent-$VER.jar" -o tools/jvmlens-agent.jar
# (b) or download the rolling pre-release jars
mkdir -p tools
gh release download latest -R alexmond/jvmlens -p 'jvmlens.jar'       -O tools/jvmlens.jar
gh release download latest -R alexmond/jvmlens -p 'jvmlens-agent.jar' -O tools/jvmlens-agent.jar
# (c) or build from source (Maven reactor — jars land in per-module target/ dirs)
git clone https://github.com/alexmond/jvmlens /tmp/jvmlens && ( cd /tmp/jvmlens && ./mvnw -q clean package )
mkdir -p tools && cp /tmp/jvmlens/jvmlens-cli/target/jvmlens.jar tools/        # CLI + MCP + trend
cp /tmp/jvmlens/jvmlens-agent/target/jvmlens-agent.jar tools/                  # the -javaagent jar
```

Nothing becomes a build dependency of the project. If `tools/jvmlens.jar` already exists,
skip this step.

## 1. Pick the mode (default: long-running monitor)

| The user wants… | Mode |
|---|---|
| **To watch it for days, then check** (the usual ask) | **A — long-running agent + trend** |
| A one-shot read of a running JVM | B — `profile <pid>` |
| To summarize a `.jfr` they already have | C — `analyze <file.jfr>` |
| This session's agent to pull profiles as tools | D — `mcp` |
| A k8s / container deployment | E — sidecar |

When unsure, do **A** — it's the track this skill exists for.

## A. Long-running monitor (headline)

Attach the agent with `history=<file.jsonl>`: it **appends** one compact sample per
interval (CPU + memory + wait), so a multi-day run accumulates instead of overwriting.
Use a longer interval (e.g. 300s) for days-long runs, and put the `.jsonl` on a path that
survives restarts.

```bash
java -javaagent:tools/jvmlens-agent.jar=out=/var/log/myapp.md,history=/var/log/myapp.jsonl,interval=300 \
     -jar build/libs/myapp.jar
```

**Pin the app scope at launch** so framework frames don't bury your code from the first sample
(no control channel needed): add `scope=app:com.example.myapp` (`+`-separated for multiple, and
`scope=exclude:<pkg>` for the exclude side).

Containers/buildpacks: set it via the JVM-standard env var instead of the launch command:

```bash
JAVA_TOOL_OPTIONS=-javaagent:/agent/jvmlens-agent.jar=out=/agent/myapp.md,history=/agent/myapp.jsonl,scope=app:com.example.myapp,interval=300
```

**Check after a few days** — `trend` reduces the whole run to a change-over-time report:

```bash
java -jar tools/jvmlens.jar trend /var/log/myapp.jsonl              # markdown digest
java -jar tools/jvmlens.jar trend -f prompt /var/log/myapp.jsonl    # wrapped for an LLM
```

The digest covers all three dimensions (CPU hot-path stability vs shift, allocation + GC
trend, lock-contention emergence) and a **hedged** retention indicator: old-object growth
alongside rising GC is flagged as *possible* growth — never a confident "leak". A window that
spans several JVM lifetimes (rolling redeploys) is segmented at the restart gaps, and each
lifetime's cold-start burst is excluded from the steady-state aggregates.

**Extended dimensions (beyond CPU/memory/wait).** The agent has per-dimension opt-in flags:
`db` (sanitized SQL + N+1 + un-batched-write), `web` (HTTP route-shape endpoints + error rate),
`messaging` (Kafka / JMS / RabbitMQ send+receive; ActiveMQ via JMS), `cache` (Spring `Cache`,
with a hit-rate flag), `mongo` (MongoDB sync-driver ops + N+1-fetch), `redis` (direct
Lettuce/Jedis commands + N+1-round-trips), `micrometer` (summarize an existing registry). Every
dimension row is **source-anchored** (`· at Repo:88`) and feeds `--hints`. Plus always-on
**external I/O**, **virtual-thread pinning**, and **deadlock** detection, and a
**cross-dimension correlation** that upgrades from co-occurrence to a **✓ confirmed chain**
(endpoint→query→GC) when the captured call-path proves it. Add the flags you want:
`...=out=…,db,web,mongo,interval=300`.

**In-flight control (no restart) + the startup fix.** Add `control=<file>` and steer the
running agent with `jvmlens control <file> <cmd>` (on the host): `start`/`stop`, `enable`/
`disable <dim>`, `settings profile|default` (sampling density), `scope app <pkg>` (filtering),
`topn db 5` (top-5 SQL with stats — returns the limits to you), `dump`, `status`. **Launch
`paused` and `start` after warm-up** — the clean fix for short cold runs that profile startup
instead of the workload.

## B. One-shot live profile

```bash
PID=$(pgrep -f 'myapp.jar' | head -1)
java -jar tools/jvmlens.jar profile -d 30 -w 5 "$PID" -a com.example.myapp -f prompt
```

`-w` warms up past classloading; `-e async` adds native frames (local pid only).

## C. Analyze an existing recording

```bash
java -jar tools/jvmlens.jar analyze run.jfr -a com.example.myapp      # add -r cpu|memory|locks|gc
```

Capture one: `java -XX:StartFlightRecording=duration=30s,filename=run.jfr,settings=profile -jar build/libs/myapp.jar …`

Flags worth knowing: `--hints` (hedged fix directions, structural-vs-inherent tagged),
`--max-tokens <n>` (budget the output), `--skip-warmup <ms>` (drop JIT/classload churn so hot
paths reflect steady state), `--source <roots>` (echo the source-line text at each `file:line`
anchor inline — `Bar.baz:88 ⟶ <code>` — comma/path-sep roots, off by default), and
`-b <before.jfr>` to **diff** two recordings (absolute-anchored, NEW/GONE, with an
extracted-helper alloc-by-type rollup). Reading the output: hot-path teasers show the **top leaves
with counts** and the **source line** (`Bar.baz:88`), `⚠ diffuse` when no leaf dominates, and
`⚠ Only N allocation samples` when per-site byte shares are noisy (the **total** stays reliable).

For a **dev one-shot** of a workload you can drive from a `main` (no JMH, no pre-recorded `.jfr`),
the sibling **jvmlens-perf** skill's `bench --main <class>` runs a warmup→timed loop and
summarizes in one command.

## D. MCP server for this session

```json
{ "mcpServers": { "jvmlens": { "command": "java", "args": ["-jar", "<abs>/tools/jvmlens.jar", "mcp"] } } }
```

Tools: `overview` → `hot_paths` / `hot_leaves` / `allocations` / `lock_contention`, plus a
live `profile` tool. Serves data only. Remote: launch over `ssh` instead of `java`.

## E. Kubernetes sidecar

jvmlens ships a standalone chart (`deploy/helm/jvmlens` + `scripts/deploy-agent.sh`) that
attaches the agent as a *separate* release without touching the app's chart. Include the
`history=`/`trend` recipe, and mind the **shared-DB caveat**: a profiled copy that reuses the
app's `envFrom` hits the **same database** — point it at a throwaway DB. Deploying into a cluster
mutates shared state, so treat it like any other infra change (confirm before applying).

## Always scope to the project's package

Pass `-a com.example.myapp` (CLI) or `scope=app:com.example.myapp` (agent) so framework
frames don't bury the app's own code. A `⚠` adequacy caveat means too few samples — record
longer or under load.

## Leave the project self-serving

Add a **Profiling (jvmlens)** block to the project's `CLAUDE.md` so future sessions know the
workflow (jar location, the `trend` command, the `-a` scope).

---

*Local overlays (optional): if a `LEARNINGS.md` or an `extensions/*.md` file sits next to this
skill, read it first — it carries machine-specific field experience and guidance that isn't part
of the published skill.*
