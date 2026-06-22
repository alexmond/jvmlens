# jvmlens

Turn JVM runtime evidence into a compact, **LLM-ready** diagnosis.

Profiling a JVM is well-served for *humans* — async-profiler, JFR, and the
commercial GUIs all produce flamegraphs and recordings. None of those formats
are built for an **LLM** to reason over: a `jfr print` dump of a short recording
is hundreds of thousands of tokens and routinely overflows a model's context
window. jvmlens reads a JFR recording and emits a few hundred tokens of ranked,
source-attributed signal you can hand straight to a coding agent.

> Early / proof-of-concept. CPU, allocation, lock-contention and GC signal from
> an existing `.jfr`. Live attach, async-profiler fidelity, JSON output and an
> MCP server are on the roadmap.

## Why

A 20-second `profile`-settings recording of a hot loop dumps **~380K tokens** of
raw `jfr print` — too big to paste. jvmlens turns the same recording into a
**~400-token** summary that names the hot application method, the leaking
allocation site, or the contended lock. For an LLM, the summary isn't just
cheaper — for non-trivial recordings it's the only input that fits.

## Build

```bash
mvn -q clean package
```

## Use

```bash
# analyze an existing JFR recording
java -jar target/jvmlens.jar analyze recording.jfr

# or during development
mvn -q spring-boot:run -Dspring-boot.run.arguments="analyze,recording.jfr"
```

Example output:

```
# JVM profile summary (recording.jfr)

Events: 1738 exec samples, 8 alloc types, 2 old-object samples, 10 GC pauses (62 ms).

## Top hot paths (application code, by sample share)
- `com.example.OrderService.reprice` — 100%  (com.example.OrderService.reprice <- ...)
...
## Suspected cause (heuristic)
- CPU-bound — `com.example.OrderService.reprice` accounts for the majority of samples.
```

## Try it end-to-end

`examples/Workload.java` is a planted-pathology workload (CPU hot path, a memory
leak, lock contention) for producing sample recordings. See `examples/README.md`.

## Roadmap

- `profile <pid>` — live attach + timed capture (JFR), and continuous + dump-on-trigger
- async-profiler fidelity (via ap-loader) behind the same interface
- `profile.json` (scoped) + `prompt.md` outputs alongside the markdown
- **MCP server** with scoped, navigable tools (progressive disclosure)
- GraalVM native image for a single drop-into-CI binary

## License

MIT — see [LICENSE](LICENSE).
