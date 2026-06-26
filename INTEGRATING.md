# Profiling your project with jvmlens

A portable, copy-paste integration guide for **any other JVM project**. Drop this
workflow into your repo and you can hand a coding agent a few hundred tokens of
ranked, source-attributed profiling signal instead of a multi-MB `jfr print` dump.

Throughout, the worked example is a project called **builder** — substitute your
own jar, pid, and package prefix. "Profile builder from builder" just means: run
the jvmlens jar from inside the builder checkout against the builder process.

- [What you need first](#what-you-need-first)
- [Pick your path (decision table)](#pick-your-path)
- [Path A — analyze a recording you already have](#path-a--analyze-a-recording)
- [Path B — profile a running builder process](#path-b--profile-a-running-process)
- [Path C — always-on, in-process (CI, containers, prod)](#path-c--always-on-in-process-agent)
- [Path D — let your coding agent pull profiles (MCP)](#path-d--mcp-for-coding-agents)
- [Path E — Kubernetes sidecar](#path-e--kubernetes-sidecar)
- [Scoping: make *your* code lead](#scoping-make-your-code-lead)
- [Feeding the output to an LLM](#feeding-the-output-to-an-llm)
- [Drop-in recipe for your repo](#drop-in-recipe-for-your-repo)
- [Paste this into your project's CLAUDE.md](#paste-this-into-your-projects-claudemd)

## What you need first

jvmlens is **not yet released to Maven Central**, but every green build on `main`
publishes a rolling **`latest`** pre-release, so you can grab the jars without
building. Java 17+.

```bash
mkdir -p tools
# CLI + MCP server (analyze/profile/watch/mcp):
curl -L -o tools/jvmlens.jar       https://github.com/alexmond/jvmlens/releases/download/latest/jvmlens.jar
# the in-process -javaagent jar (Path C):
curl -L -o tools/jvmlens-agent.jar https://github.com/alexmond/jvmlens/releases/download/latest/jvmlens-agent.jar
# the JMH profiler jar (optimize loop, optional):
curl -L -o tools/jvmlens-jmh.jar   https://github.com/alexmond/jvmlens/releases/download/latest/jvmlens-jmh.jar
```

These URLs are stable — `latest` always points at the most recent green build.
It's a **pre-release**; APIs may change until the first tagged release.

<details><summary>Or build from source</summary>

```bash
git clone https://github.com/alexmond/jvmlens && cd jvmlens
./mvnw -q clean package
#   jvmlens-cli/target/jvmlens.jar           <- the CLI + MCP server
#   jvmlens-agent/target/jvmlens-agent.jar   <- the in-process -javaagent jar
#   jvmlens-jmh/target/jvmlens-jmh.jar       <- the JMH profiler jar
```

</details>

Keep `jvmlens.jar` on a shared path or copy it into your project (e.g.
`builder/tools/jvmlens.jar`). Nothing about jvmlens needs to be a dependency of
your build — it consumes JFR, which the JDK already produces.

> jvmlens never calls an LLM and never sends your recording anywhere. Every path
> below runs locally and emits text you choose to share.

## Pick your path

| You have… | You want… | Path |
|---|---|---|
| A `.jfr` file already | A one-shot summary | **A** — `analyze` |
| A running JVM (local or one `ssh`/`kubectl exec` away) | An on-demand snapshot | **B** — `profile <pid>` |
| A long-running service / CI job / container | Continuous summaries with zero attach | **C** — `-javaagent` |
| A coding agent (Claude Code, etc.) that should fetch profiles itself | Profiling exposed as tools | **D** — `mcp` |
| A k8s workload | A sidecar that profiles without touching your app's chart | **E** — Helm |

Paths compose — e.g. run the agent (C) in k8s (E), or point an MCP client (D) at a
remote host over `ssh`.

## Path A — analyze a recording

If builder already produces a JFR (CI artifact, a crash dump, `-XX:StartFlightRecording`),
just summarize it:

```bash
java -jar tools/jvmlens.jar analyze builder-run.jfr               # markdown (default)
java -jar tools/jvmlens.jar analyze -r cpu builder-run.jfr        # focus: cpu|memory|locks|gc
java -jar tools/jvmlens.jar analyze -f prompt builder-run.jfr     # wrapped as an LLM task
```

To produce a recording around a builder workload:

```bash
java -XX:StartFlightRecording=duration=30s,filename=builder-run.jfr,settings=profile \
     -jar build/libs/builder.jar build ./big-project
java -jar tools/jvmlens.jar analyze builder-run.jfr
```

### Optimize → measure loop (diff, gate, JMH)

When you're making something *faster*, point `analyze` at a **directory** (a JMH `-prof jfr`
run — every fork merged), **diff** a before/after, **gate** a regression in CI, and get hedged
fix directions:

```bash
java -jar tools/jvmlens.jar analyze /tmp/run-after -a com.example.builder        # merge JMH forks
java -jar tools/jvmlens.jar analyze --baseline /tmp/run-before /tmp/run-after     # diff (absolute-anchored)
java -jar tools/jvmlens.jar analyze -b before.jfr after.jfr --assert "alloc-pct < 0, gc-pct < 10"  # CI gate (exit ≠0)
java -jar tools/jvmlens.jar analyze builder-run.jfr --hints                       # hedged [possible] fix directions
java -jar tools/jvmlens.jar analyze builder-run.jfr --top-k 3                     # or --max-tokens 250 to budget size
```

Or print the summary **inline from a JMH benchmark** with the profiler plugin (put
`jvmlens-<version>-jmh.jar` on the benchmark classpath):

```bash
java -cp benchmarks.jar:tools/jvmlens-<version>-jmh.jar org.openjdk.jmh.Main \
  -prof "org.alexmond.jvmlens.jmh.JvmlensProfiler:appPackage=com.example.builder;report=cpu"
```

## Path B — profile a running process

`profile <pid>` attaches to a live JVM, records a timed window, and summarizes it —
no pre-recorded file, no JMX, no start-up flags on the target.

```bash
PID=$(pgrep -f 'builder.jar' | head -1)            # find builder's pid
java -jar tools/jvmlens.jar profile "$PID"                  # 20s, markdown
java -jar tools/jvmlens.jar profile -d 30 -w 5 "$PID"      # skip 5s startup, record 30s
java -jar tools/jvmlens.jar profile -d 30 -k builder.jfr "$PID"   # also keep the .jfr
java -jar tools/jvmlens.jar profile -e async -d 30 "$PID"  # async-profiler: native frames (local pid only)
```

**Remote builder** — run jvmlens *on the host* through whatever access you already
have; only the few-hundred-token summary travels back:

```bash
ssh build-host    'java -jar jvmlens.jar profile $(pgrep -f builder.jar) -f prompt'
kubectl exec builder-pod -- java -jar /tools/jvmlens.jar profile 1 -r cpu
docker exec builder      java -jar /tools/jvmlens.jar watch 1 --on-gc-ms 200
```

Use `-w/--warmup` so you measure the steady-state build, not classloading. A summary
built from too few samples is flagged with a `⚠` adequacy caveat — record longer or
under load when you see it.

## Path C — always-on, in-process (agent)

For a service, a CI job, or a container, load the agent. It keeps a JFR ring buffer
*inside* builder and writes a fresh summary to a file every interval — no attach.

```bash
java -javaagent:tools/jvmlens-<version>-agent.jar=out=/var/log/builder-profile.md,interval=60 \
     -jar build/libs/builder.jar
```

Agent options (comma-separated `key=value`):

| Key | Meaning | Default |
|---|---|---|
| `out` | File the **latest** summary is written to (overwritten each interval) | `jvmlens-summary.md` |
| `history` | JSONL file the agent **appends** one sample to per interval — for multi-day trends (see below) | — |
| `interval` | Seconds between summaries | `60` |
| `settings` | JFR config: `profile` (denser) or `default` (lighter) | `profile` |
| `snapshot` | `Class#method` to capture argument digests for (semicolon-separate several) | — |
| `db` / `web` / `messaging` / `cache` | Instrument JDBC / HTTP / Kafka-JMS / Spring-Cache (one dimension each) | off |
| `micrometer` | Summarize an existing Micrometer registry (no extra instrumentation) | off |
| `paused` | Launch **without** emitting — `start` it after warm-up (skips startup noise) | off |
| `control` | A file the agent watches for **in-flight** commands (see *Runtime control* below) | — |

Deadlock detection runs **always** (cheap, agent-side `ThreadMXBean`) — no option needed.

```bash
# everything on, paused until you start it, controllable:
java -javaagent:tools/jvmlens-<version>-agent.jar=out=/var/log/builder.md,db,web,paused,control=/var/log/builder.control \
     -jar build/libs/builder.jar
```

### Runtime control (in-flight, no restart)

With `control=<file>` set, steer the running agent by issuing commands on the host (the
`jvmlens control` CLI appends to the file and reads the agent's state back):

```bash
java -jar tools/jvmlens.jar control /var/log/builder.control start          # begin after warm-up
java -jar tools/jvmlens.jar control /var/log/builder.control enable db      # turn a dimension on
java -jar tools/jvmlens.jar control /var/log/builder.control topn db 5      # top 5 SQL with stats
java -jar tools/jvmlens.jar control /var/log/builder.control settings default  # lighter sampling
java -jar tools/jvmlens.jar control /var/log/builder.control scope app com.example.builder
java -jar tools/jvmlens.jar control /var/log/builder.control dump           # emit now
java -jar tools/jvmlens.jar control /var/log/builder.control status         # read state back
```

Commands: `start`/`stop`, `clear`, `dump`, `enable`/`disable <dim>`, `settings profile|default`,
`interval <s>`, `scope app|exclude <prefix>` / `scope reset`, `topn [<category>] <n>`, `status`.
No ports, no JMX. **`paused` + `start`-after-warm-up is the clean fix for short cold runs that
otherwise profile startup, not the workload.**

In containers you usually don't control the launch command — set it via the
JVM-standard env var (honoured by buildpacks and `java -jar` alike):

```bash
JAVA_TOOL_OPTIONS=-javaagent:/agent/jvmlens-agent.jar=out=/agent/builder.md,interval=60
```

**Variable snapshots** (a correctness aid, not just performance): `snapshot=com.example.builder.Planner#schedule`
makes the agent append a per-argument digest (distinct values, null rate, numeric
range) for that method — answer "what values flow through this?" without stopping
the app. Method arguments need no debug info.

### Long-running monitor (let it run for days, then check)

`out` only keeps the *latest* window. For a multi-day watch, add `history=<file.jsonl>`:
the agent **appends** one compact sample per interval (CPU + memory + wait), so the run
accumulates instead of overwriting. Use a longer `interval` (e.g. 300s) for days-long runs.

```bash
java -javaagent:tools/jvmlens-<version>-agent.jar=out=/var/log/builder.md,history=/var/log/builder.jsonl,interval=300 \
     -jar build/libs/builder.jar
```

Let it run a few days, then **check** it — `trend` reduces the whole run to a
change-over-time report (what moved, not a single snapshot):

```bash
java -jar tools/jvmlens.jar trend /var/log/builder.jsonl              # markdown digest
java -jar tools/jvmlens.jar trend -f prompt /var/log/builder.jsonl    # wrapped for an LLM
```

The digest covers all three dimensions (CPU hot-path stability/shift, allocation + GC
trend, lock-contention emergence) and a **hedged** retention indicator: old-object growth
alongside rising GC is flagged as *possible* retention growth — never a confident "leak".
Mount `/var/log/builder.jsonl` on a volume that survives restarts so the history isn't lost.

## Path D — MCP for coding agents

`jvmlens mcp` runs a [Model Context Protocol](https://modelcontextprotocol.io)
server over stdio so a coding agent pulls only the slice it needs (`overview` →
`hot_paths` / `hot_leaves` / `allocations` / `lock_contention`), plus a live
`profile` tool that captures a local pid on demand. It serves structured data only —
it never calls an LLM, so recordings stay on the host.

Register it with your MCP client (Claude Code shown):

```json
{ "mcpServers": {
  "jvmlens": { "command": "java", "args": ["-jar", "/abs/path/builder/tools/jvmlens.jar", "mcp"] }
} }
```

Remote builder, no extra ports — the client launches jvmlens over `ssh`:

```json
{ "mcpServers": {
  "builder-prod": { "command": "ssh", "args": ["build-host", "java", "-jar", "jvmlens.jar", "mcp"] }
} }
```

Then ask the agent: *"Use the jvmlens `profile` tool on builder's pid and tell me the
hot path."*

## Path E — Kubernetes sidecar

`deploy/helm/jvmlens` is a **standalone** chart: it runs your image with the agent
attached as a *separate* release, so your app's own chart is untouched. An init
container drops the agent jar into a shared volume; the app container picks it up via
`JAVA_TOOL_OPTIONS`; a tiny sidecar serves the rolling summary over HTTP.

```bash
# build + push the agent image, then install against your image
scripts/deploy-agent.sh --release builder-profiled --namespace builder \
  --target-image my-registry/builder:1.0
```

See `deploy/helm/jvmlens/README.md` for direct `helm upgrade --install` use and the
values you can override. **Caution:** if the profiled copy reuses your app's
`envFrom`, it hits the **same database** — point it at a throwaway DB or run
read-only.

## Scoping: make *your* code lead

By default jvmlens treats a frame as application code unless it's the JDK or a common
framework (Spring, Hibernate, Jackson, Netty, Groovy, JDBC drivers, …). For a foreign
project this is the single most useful knob — restrict attribution to your packages so
your code leads the ranking instead of framework noise. Works on `analyze` and `profile`:

```bash
java -jar tools/jvmlens.jar analyze -a com.example.builder builder-run.jfr   # include-only
java -jar tools/jvmlens.jar analyze -x com.thirdparty       builder-run.jfr   # exclude more
```

Both flags are repeatable and comma-separable. The MCP tools accept the same as
`appPackages` / `exclude`.

## Feeding the output to an LLM

Three renderings, same signal from one analysis pass (`-f`):

- `md` (default) — compact markdown, readable by humans and agents.
- `json` — a scoped object for tooling.
- `prompt` — the markdown wrapped in a ready-to-paste LLM task instruction.

```bash
java -jar tools/jvmlens.jar profile "$PID" -f prompt | pbcopy   # paste into any chat
```

Because the summary is a few hundred tokens (a raw `jfr print` of the same recording
is hundreds of thousands and overflows the context window), you can also just inline
it into an agent prompt or commit it as a CI artifact.

## Drop-in recipe for your repo

A tiny script your team (and your agents) can run without remembering flags. Save as
`builder/tools/profile.sh`:

```bash
#!/usr/bin/env bash
# Profile the running builder JVM and print an LLM-ready summary.
set -euo pipefail
JVMLENS=${JVMLENS:-tools/jvmlens.jar}
PID=$(pgrep -f 'builder.jar' | head -1) || { echo "builder not running"; exit 1; }
exec java -jar "$JVMLENS" profile "$PID" -d "${DURATION:-20}" -w "${WARMUP:-3}" \
  -a "${APP_PKG:-com.example.builder}" -f "${FORMAT:-prompt}"
```

```bash
chmod +x tools/profile.sh && ./tools/profile.sh        # -> paste-ready summary
```

## Paste this into your project's CLAUDE.md

So a coding agent working in builder knows how to profile it:

```markdown
## Profiling (jvmlens)

This project can be profiled with jvmlens — it turns a JFR recording into a
~400-token, source-attributed summary instead of a multi-MB dump.

- The jar lives at `tools/jvmlens.jar` (built from github.com/alexmond/jvmlens; Java 17+).
- **One-shot, running JVM:** `./tools/profile.sh` (wraps `jvmlens profile <pid> -a com.example.builder -f prompt`).
- **From a .jfr file:** `java -jar tools/jvmlens.jar analyze <file.jfr> -a com.example.builder`.
- **Focus a concern:** add `-r cpu|memory|locks|gc`.
- Always scope with `-a com.example.builder` so our code leads, not framework frames.
- A `⚠` adequacy caveat means too few samples — record longer or under load.
```

---

For the full flag reference see [`docs/modules/ROOT/pages/usage.adoc`](docs/modules/ROOT/pages/usage.adoc);
for the design rationale, [`DESIGN.md`](DESIGN.md).
