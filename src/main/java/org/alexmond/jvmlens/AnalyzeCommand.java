package org.alexmond.jvmlens;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
		description = "Summarize a JFR recording (or a JMH -prof jfr directory) into an LLM-ready report, "
				+ "or diff two recordings.")
public class AnalyzeCommand implements Callable<Integer> {

	@Parameters(index = "0", paramLabel = "<file.jfr|dir>",
			description = "A JFR recording, or a directory of them (a JMH -prof jfr run, merged); the 'after' when diffing.")
	Path file;

	@Option(names = { "-b", "--baseline" }, paramLabel = "<before.jfr|dir>",
			description = "Diff <file.jfr|dir> against this baseline: name what changed (the optimize→measure loop).")
	Path baseline;

	@Option(names = { "--assert" }, paramLabel = "<rules>",
			description = "CI perf-gate over the diff (needs --baseline): `metric < n` rules, comma-separated "
					+ "(gc-ms, gc-pct, alloc-pct, oldobj-delta, regression-pp, new-hotpath-pp). Non-zero exit on regression.")
	String assertSpec;

	@Option(names = { "--hints" },
			description = "Append a hedged `[possible]` fix-direction section (off by default — keeps output clean-data-only).")
	boolean hints;

	@Mixin
	OutputOptions output;

	@Override
	public Integer call() throws Exception {
		List<Path> afterFiles = readable(file);
		if (afterFiles.isEmpty()) {
			System.err.println("jvmlens: no readable .jfr at: " + file);
			return 2;
		}
		if (baseline != null) {
			List<Path> beforeFiles = readable(baseline);
			if (beforeFiles.isEmpty()) {
				System.err.println("jvmlens: no readable baseline .jfr at: " + baseline);
				return 2;
			}
			ProfileSummary before = Summarizer.analyze(beforeFiles, output.scope(), Recordings.label(baseline, file));
			ProfileSummary after = Summarizer.analyze(afterFiles, output.scope(), Recordings.label(file, baseline));
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
		ProfileSummary summary = Summarizer.analyze(afterFiles, output.scope(), Recordings.label(file, null));
		System.out.print(render(summary));
		return 0;
	}

	/**
	 * Render the summary, appending hedged fix hints when {@code --hints} is set
	 * (md/prompt).
	 */
	private String render(ProfileSummary summary) {
		if (hints && output.format != Summarizer.Format.JSON) {
			String body = Summarizer.render(summary, Summarizer.Format.MARKDOWN, output.report)
					+ FixHints.render(summary);
			return (output.format == Summarizer.Format.PROMPT) ? Renderers.promptOf(body) : body;
		}
		return Summarizer.render(summary, output.format, output.report);
	}

	private static List<Path> readable(Path arg) throws java.io.IOException {
		return Recordings.expand(arg).stream().filter(Files::isReadable).toList();
	}

}
