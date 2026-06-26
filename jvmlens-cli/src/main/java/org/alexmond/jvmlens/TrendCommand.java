package org.alexmond.jvmlens;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Reduce a long-running monitor's accumulated history (the JSONL file the agent appends
 * one {@link History.Sample} to per interval) into an LLM-ready change-over-time report.
 * This is the "check after a few days" end of the long-running track.
 */
@Component
@Command(name = "trend", mixinStandardHelpOptions = true,
		description = "Summarize a long-running monitor's history (JSONL) into a change-over-time report.")
public class TrendCommand implements Callable<Integer> {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Parameters(index = "0", paramLabel = "<history.jsonl>",
			description = "History file the agent appended (one JSON sample per line).")
	Path file;

	@Option(names = { "-f", "--format" }, paramLabel = "<format>",
			description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
	Summarizer.Format format = Summarizer.Format.MARKDOWN;

	@Override
	public Integer call() throws Exception {
		if (!Files.isReadable(file)) {
			System.err.println("jvmlens: cannot read history file: " + file);
			return 2;
		}
		List<History.Sample> samples = new ArrayList<>();
		long skipped = 0;
		for (String line : Files.readAllLines(file)) {
			if (line.isBlank()) {
				continue;
			}
			try {
				samples.add(MAPPER.readValue(line, History.Sample.class));
			}
			catch (Exception ex) {
				skipped++;
			}
		}
		if (samples.isEmpty()) {
			System.err.println("jvmlens: no parseable history samples in " + file);
			return 2;
		}
		if (skipped > 0) {
			System.err.println("jvmlens: skipped " + skipped + " unparseable history line(s)");
		}
		System.out.print(render(samples));
		return 0;
	}

	private String render(List<History.Sample> samples) {
		return switch (this.format) {
			case JSON -> History.toJsonArray(samples);
			case PROMPT -> Renderers.promptOf(History.digest(samples));
			default -> History.digest(samples);
		};
	}

}
