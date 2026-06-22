# examples

`Workload.java` is a dependency-free, single-file workload that plants one known
pathology per scenario — useful for producing sample JFR recordings to run
`jvmlens analyze` against.

| Scenario | Planted bug (ground truth) |
|---|---|
| `cpu`   | `expensiveHashLoop()` re-hashes 2000× — CPU hot path |
| `alloc` | `handleUpload()` retains 64KB payloads in static `LEAK_CACHE` forever |
| `lock`  | 16 threads contend `SHARED_LOCK` in `criticalSection()` |

## Produce a recording and analyze it

```bash
# 1. record a 20s run under JFR (single-file source launch, JDK 17+)
java -XX:StartFlightRecording=duration=25s,filename=recording-cpu.jfr,settings=profile \
     Workload.java cpu 20

# 2. summarize it
java -jar ../target/jvmlens.jar analyze recording-cpu.jfr
```

The summary should name `Workload.expensiveHashLoop` (cpu), `Workload.handleUpload`
(alloc), or `Workload.criticalSection` (lock) as the cause.
