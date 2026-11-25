# Running jvmlens as part of a build

Research note (2026-11-24, epic #204): can jvmlens run inside a Maven/CI build — as a Maven plugin or
otherwise — and how does JMeter, the closest prior art, do it? Status: research only, nothing
built. Each claim is marked **verified** (run or read in code here) or **assumed**.

## Summary

- Running jvmlens in a build **works today with no new code** (verified): record the test JVM
  with JFR via surefire `argLine`, fetch the published CLI with `dependency:copy`, run
  `analyze` with `exec:exec`.
- The plugin mechanics are the easy part. Two things decide whether a *gate* is honest:
  1. **Where the baseline comes from** — a fresh build has no "before" recording.
  2. **What workload is recorded** — unit tests are short, cold and fixture-heavy.
- Split it: **report in a build** is cheap and useful now; **gate in a build** is only honest
  on a `bench`/JMH workload with a stored baseline, or with absolute (no-baseline) limits.
- JMeter confirms the split: its build plugin gates on **absolute** limits only; comparison
  against a previous run lives in the **CI server** (Jenkins), not in Maven.

## What exists today (verified in code)

- The whole gate lives in the engine: the fork merge (`Recordings`), `ProfileDiff`, and
  `PerfGate.evaluate(before, after, spec)`. `AnalyzeCommand` is argument handling. A plugin
  depending on `jvmlens-engine` only would be a thin wrapper.
- `--assert` needs `--baseline`, and the baseline must be a `.jfr` (or a directory of them).
  There is no reader for a saved summary, so a small committed baseline file is not possible
  yet.
- The JMH profiler prints a measured A/B verdict with `baseline=`, but has **no option that
  fails the build** (`KNOWN_KEYS` = appPackage, report, keep, baseline, socketio).

## Probe: zero-code Maven recipe (verified)

Scratch project, one JUnit test spinning a CPU loop for 3 s, jvmlens-cli 0.3.1 from Central,
JDK 25. `mvn verify` printed a full summary, including
`⚠ Recorded from a test run (Maven Surefire)`.

```xml
<plugin><artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>-XX:StartFlightRecording=filename=${project.build.directory}/tests-${surefire.forkNumber}.jfr,settings=profile</argLine>
  </configuration></plugin>
<plugin><artifactId>maven-dependency-plugin</artifactId>
  <executions><execution><id>jvmlens</id><phase>generate-test-resources</phase><goals><goal>copy</goal></goals>
    <configuration>
      <artifactItems><artifactItem><groupId>org.alexmond</groupId><artifactId>jvmlens-cli</artifactId>
        <version>${jvmlens.version}</version><destFileName>jvmlens.jar</destFileName></artifactItem></artifactItems>
      <outputDirectory>${project.build.directory}/tools</outputDirectory>
    </configuration></execution></executions></plugin>
<plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId>
  <executions><execution><id>jvmlens-analyze</id><phase>verify</phase><goals><goal>exec</goal></goals>
    <configuration><executable>java</executable><arguments>
      <argument>-jar</argument><argument>${project.build.directory}/tools/jvmlens.jar</argument>
      <argument>analyze</argument><argument>-a</argument><argument>demo</argument>
      <argument>${project.build.directory}/tests-1.jfr</argument>
    </arguments></configuration></execution></executions></plugin>
```

Gotchas found while setting it up (verified):

- **JFR does not create a missing directory.** `filename=target/jfr/x.jfr` with no `target/jfr`
  kills the test JVM at startup ("Error occurred during initialization of VM").
- Surefire substitutes **`${surefire.forkNumber}`** in `argLine`, not `@{surefire.forkNumber}`.
- `jvmlens-cli` is a Boot fat jar: run it with `exec:exec` + `java -jar`, not `exec:java`
  (assumed — not tried).

Assumed, not tested:

- `exec:exec` fails the build on a non-zero exit, so `--assert` gating works through the recipe.
- A plugin-set `argLine` collides with JaCoCo's `argLine` and any pinned one (this repo already
  combines them by hand in the parent pom).
- The probe reached 304 samples only because of the 3 s loop; typical unit tests fall under
  the 200-sample caveat.

## How JMeter does it (prior art)

From docs, javadoc and issue trackers — see Sources.

- **Third-party plugin, not Apache.** `jmeter-maven-plugin` (groupId `com.lazerycode`).
- **Three goals across the lifecycle:**
  - `configure` (compile): downloads JMeter and sets up a private copy under `target/`, plus
    extension jars (`jmeterExtensions`).
  - `jmeter`: runs the `.jmx` test plans — a purpose-written load test, not the unit tests.
  - `results` (verify): reads the result files and fails the build.
- **The build gate is absolute.** `errorRateThresholdInPercent` (default 0: any failed request
  fails the build). Fail-on-error was added later (their Issue 265). Community examples
  disagree on its units (0.1 described as 10%).
- **Baseline comparison lives in the CI server.** The Jenkins Performance plugin's relative
  thresholds compare against the previous build or a chosen "known good" build. It has a bug
  history: relative thresholds not failing the build (JENKINS-31957, Won't Fix) and a
  comparison table showing zeros (JENKINS-57120). Common advice: compare against a fixed
  known-good baseline, not the previous build, so slowness cannot creep in a few percent at a
  time.
- **Taurus (`bzt`)** also uses absolute pass/fail rules (e.g. average response time of a page
  above N ms) and exits with code 3 to fail the CI step. Pitfalls: a rule with a mistyped label
  silently does nothing; in the cloud the rules are skipped with only a warning.

## How JMH projects do it: OGNL and Thymeleaf (read from GitHub, 2026-11-24)

### OGNL (`orphan-oss/ognl`) — benchmarks in the build, with a committed baseline

- A `benchmarks` module inside the reactor (JMH + shade, main class `ognl.Run`), with
  `maven-deploy-plugin` skip so it is never published.
- A Maven profile `-Pbenchmarks` binds `exec:exec` to `verify`:
  `java -jar target/<name>.jar ${benchmarks.jmhArgs}`.
- `Run.main` runs JMH, writes JSON results to `target/ognl-runtime-benchmark-results.json`, and
  compares against a **committed** baseline `benchmarks/etc/ognl-runtime-benchmark-baseline.json`
  (plain JMH JSON scores).
  - Regression = primary score more than **10% worse** (fixed threshold, the JMH error band is
    ignored); `System.exit(1)` fails the build.
  - `OPTIONS=publishSummary` writes a markdown table to `$GITHUB_STEP_SUMMARY`;
    `OPTIONS=generateBaseline` only writes results.
- CI: `performance.yml` runs `-f 10` on every push to `main` (not on PRs) and uploads the
  results; `performance-baseline.yml` (manual) regenerates the baseline as an artifact, which
  someone then commits.
- What it has: numbers only — throughput per benchmark, no "where" or "why". A benchmark missing
  from the current run prints `NOT FOUND` but does not fail.
- Weak spots: a fixed 10% with no error band, and a baseline from one GitHub runner compared
  against another runner's hardware.

### Thymeleaf (`thymeleaf/thymeleaf-benchmarks`) — a separate repo, manual

- Not in the main `thymeleaf/thymeleaf` repo (that has only `tests/` modules). A separate repo,
  last pushed 2022-12, with four standalone JMH modules (`thymeleaf-benchmark01..04`, OGNL /
  Spring 3 / Spring 4 / SpEL-compiled).
- Compares Thymeleaf **versions** via Maven profiles (`-P 2.1.4`, `-P 3.0.0`); run by hand with
  `-rf csv`, results combined and plotted with gnuplot. Committed CSVs and PNGs.
- No build integration, no CI, no gate.

### What this means for jvmlens

- OGNL is the template: a benchmarks module, a `-Pbenchmarks` profile running in `verify`, a
  small committed baseline, and a markdown summary to the CI step. jvmlens can drop into that
  pattern with one dependency (`jvmlens-jmh` from Central — verified in a scratch module).
- What jvmlens adds on top: the **where/why** (hot paths, allocation sites) next to each number,
  a **significance call** from the JMH error band instead of a fixed 10%, and loud failure when
  a benchmark or baseline is missing.
- What is missing in jvmlens to fit: the profiler only prints to stdout (no report file), no
  `gate=` to fail the build, and its baseline is a `.jfr` — too big to commit, unlike OGNL's
  JSON. A small committable baseline is what would let jvmlens match OGNL's setup. The #200 design
  (PR #206) proposes the JMH scores and error bands in one text file, deferring hot paths.

## Options for jvmlens

| Option | Cost | Payoff | Notes |
|---|---|---|---|
| exec + CLI recipe (docs only) | Tiny | Works today on any Maven build | Verified; document with the gotchas above. |
| Absolute-limit `--assert` (no baseline) | Small | Gating in any build today via the recipe | JMeter's model. Check whether `PerfGate` can run without a "before" (not checked). |
| `gate=` on the JMH profiler | Small | The JMH loop can fail a build | Cheapest real gap; the verdict is already computed. |
| GitHub Action | Small–medium | Any build tool; PR comment; baseline kept as an artifact | Solves baseline storage — JMeter's "CI server holds history" answer. Supports a fixed known-good baseline. |
| Maven plugin (prepare / analyze / check) | Medium | Clean pom usage; auto-wires `argLine` and the directory | One more Central artifact per release; must merge `argLine` with JaCoCo. Little gain over the recipe until a committable baseline exists. |
| JUnit 5 extension | Medium | Per-test recordings | Same unit-test noise problem. Low value. |
| Gradle plugin | Medium | Gradle users | Same as the Maven plugin; wait for demand. |

## Recommendation

1. Document the exec recipe in `integrating.adoc`, with the two JFR/surefire gotchas.
2. Add absolute-limit gates (no baseline) and `gate=` on the JMH profiler — small, and they
   make build gating possible without stored history.
3. If baseline comparison in CI matters: a GitHub Action that keeps a known-good baseline as an
   artifact.
4. A Maven plugin later, following the three-goal shape (JaCoCo / JMeter), once a committable
   summary baseline exists.

Lessons to carry over from JMeter's pitfalls: a rule naming a row or metric that does not exist
must fail loudly (as unknown options already do), and units belong in the rule text.

## Sources

- [jmeter-maven-plugin: Basic Configuration (wiki)](https://github.com/jmeter-maven-plugin/jmeter-maven-plugin/wiki/Basic-Configuration)
- [CheckResultsMojo (`results` goal), 3.4.0 javadoc](https://javadoc.io/static/com.lazerycode.jmeter/jmeter-maven-plugin/3.4.0/com/lazerycode/jmeter/mojo/CheckResultsMojo.html)
- [ConfigureJMeterMojo, 3.8.0 javadoc](https://javadoc.io/static/com.lazerycode.jmeter/jmeter-maven-plugin/3.8.0/com/lazerycode/jmeter/mojo/ConfigureJMeterMojo.html)
- [TestFailureDecider, 3.8.0 javadoc](https://javadoc.io/static/com.lazerycode.jmeter/jmeter-maven-plugin/3.8.0/com/lazerycode/jmeter/testrunner/TestFailureDecider.html)
- [jmeter-maven-plugin CHANGELOG](https://www.github.com/jmeter-maven-plugin/jmeter-maven-plugin/blob/main/CHANGELOG.md)
- [Apache wiki: the JMeter Maven plugin is a 3rd-party product](https://cwiki-test.apache.org/confluence/x/uCogBw)
- [Units example (0.1 described as 10%)](https://coderapp.vercel.app/answer/78636105)
- [Using JMeter with Jenkins](https://www.jenkins.io/doc/book/using/using-jmeter-with-jenkins/)
- [BlazeMeter: Jenkins Performance plugin](https://blazemeter.com/blog/jenkins-performance-plugin)
- [JENKINS-31957: relative thresholds not failing the build](https://issues.jenkins.io/browse/JENKINS-31957)
- [JENKINS-57120: comparison table always zero](https://issues.jenkins.io/browse/JENKINS-57120)
- [Performance testing in CI/CD (baseline vs previous build)](https://dev.to/beefedai/performance-testing-in-cicd-gatekeeping-speed-2go8)
- [Taurus Pass/Fail Criteria](https://gettaurus.org/docs/PassFail/)
- [BlazeMeter: JMeter + GitHub, Taurus exit code 3](https://www.blazemeter.com/blog/jmeter-github-testing)
- [Taurus label mismatch](https://groups.google.com/g/codename-taurus/c/RP20IbGs6_I)
