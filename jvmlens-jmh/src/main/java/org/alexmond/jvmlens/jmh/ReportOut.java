package org.alexmond.jvmlens.jmh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.ProfilerException;

/**
 * The {@code out=DIR} option of {@link JvmlensProfiler} (#199): writes the markdown the
 * profiler printed for one benchmark to {@code DIR/BenchmarkParams.id().md}.
 *
 * <p>
 * {@code out=} is always a <em>directory</em>. JMH builds a fresh profiler for every
 * benchmark and every {@code @Param} combination with the same option string, so no
 * instance knows whether it is the only one — a single file would be overwritten by the
 * next benchmark. {@link BenchmarkParams#id()} (benchmark, mode and each param, URL
 * encoded) is the name JMH's own JFR and async profilers use, so the files line up with
 * theirs.
 *
 * <p>
 * Failures split by time. A path that cannot be a directory is a
 * {@link ProfilerException} at construction, which JMH runs before any benchmark, so it
 * fails fast like an unknown option. A write that fails after the benchmark ran prints a
 * warning and returns: the report is already on stdout, and throwing would cost the rest
 * of the run.
 */
final class ReportOut {

	private final Path dir;

	private ReportOut(Path dir) {
		this.dir = dir;
	}

	/**
	 * Validates and creates the {@code out=} directory.
	 * @param value the option value
	 * @return the writer
	 * @throws ProfilerException if the value is blank or cannot be a directory
	 */
	static ReportOut of(String value) throws ProfilerException {
		if (value == null || value.isBlank()) {
			throw new ProfilerException("jvmlens: `out=` needs a directory, e.g. out=target/jvmlens");
		}
		Path dir = Path.of(value);
		if (Files.exists(dir) && !Files.isDirectory(dir)) {
			throw new ProfilerException("jvmlens: `out=" + value + "` is not a directory — out= names a directory "
					+ "and writes one <benchmark>.md per benchmark into it");
		}
		try {
			Files.createDirectories(dir);
		}
		catch (IOException ex) {
			throw new ProfilerException("jvmlens: cannot create the `out=` directory " + dir + ": " + ex);
		}
		return new ReportOut(dir);
	}

	/**
	 * Writes {@code text} as this benchmark's report, replacing any earlier one, and says
	 * where on stdout — or says why not. Never throws.
	 * @param params the benchmark whose report this is
	 * @param text exactly the markdown the profiler printed
	 */
	void write(BenchmarkParams params, String text) {
		Path file = this.dir.resolve(params.id().replace('%', '_') + ".md");
		try {
			Files.writeString(file, text, StandardCharsets.UTF_8);
			System.out.println("jvmlens: report written to " + file);
		}
		catch (IOException | RuntimeException ex) {
			System.out.println("jvmlens: ⚠ could not write the report to " + file + " (" + ex
					+ ") — it is only in the output above");
		}
	}

}
