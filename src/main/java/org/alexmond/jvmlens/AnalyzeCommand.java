package org.alexmond.jvmlens;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
		description = "Summarize a JFR recording into an LLM-ready report (or diff two recordings).")
public class AnalyzeCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<file.jfr>",
			description = "JFR recording to analyze (the 'after' when diffing).")
	Path file;

	@Option(names = { "-b", "--baseline" }, paramLabel = "<before.jfr>",
			description = "Diff <file.jfr> against this baseline recording: name what changed (the optimize→measure loop).")
	Path baseline;

	@Option(names = { "--assert" }, paramLabel = "<rules>",
			description = "CI perf-gate over the diff (needs --baseline): `metric < n` rules, comma-separated "
					+ "(gc-ms, gc-pct, oldobj-delta, regression-pp, new-hotpath-pp). Non-zero exit on regression.")
	String assertSpec;

	@Mixin
	OutputOptions output;

	@Override
	public Integer call() throws Exception {
		if (!Files.isReadable(file)) {
			System.err.println("jvmlens: cannot read JFR file: " + file);
			return 2;
		}
		if (baseline != null) {
			if (!Files.isReadable(baseline)) {
				System.err.println("jvmlens: cannot read baseline JFR file: " + baseline);
				return 2;
			}
			ProfileSummary before = Summarizer.analyze(baseline, output.scope());
			ProfileSummary after = Summarizer.analyze(file, output.scope());
			String delta = ProfileDiff.diff(before, after);
			if (assertSpec != null) {
				PerfGate.Result gate = PerfGate.evaluate(before, after, assertSpec);
				System.out.print(delta + "\n" + gate.report());
				return gate.passed() ? 0 : 1;
			}
			System.out.print((output.format == Summarizer.Format.PROMPT) ? Renderers.promptOf(delta) : delta);
			return 0;
		}
		if (assertSpec != null) {
			System.err.println("jvmlens: --assert needs --baseline (it gates a before→after diff)");
			return 2;
		}
		System.out.print(Summarizer.summarize(file, output.format, output.scope(), output.report));
		return 0;
	}

}
