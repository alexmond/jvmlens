package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Option;

/**
 * The output knobs shared by every command that summarizes a recording — format, report
 * focus, and application-code scoping. Mixed into {@code analyze}, {@code profile}, and
 * {@code watch} via {@code @Mixin} so the options stay defined once and consistent.
 */
public class OutputOptions {

	@Option(names = { "-f", "--format" }, paramLabel = "<format>",
			description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
	Summarizer.Format format = Summarizer.Format.MARKDOWN;

	@Option(names = { "-r", "--report" }, paramLabel = "<report>",
			description = "Report focus: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
	Summarizer.Report report = Summarizer.Report.FULL;

	@Option(names = { "-a", "--app-package" }, paramLabel = "<prefix>", split = ",",
			description = "Treat only these package prefixes as application code (repeatable).")
	List<String> appPackages = new ArrayList<>();

	@Option(names = { "-x", "--exclude" }, paramLabel = "<prefix>", split = ",",
			description = "Extra package prefixes to treat as non-application code (repeatable).")
	List<String> excludePackages = new ArrayList<>();

	/**
	 * The application-code scope selected by {@code --app-package} / {@code --exclude}.
	 */
	Scope scope() {
		return Scope.of(this.appPackages, this.excludePackages);
	}

}
