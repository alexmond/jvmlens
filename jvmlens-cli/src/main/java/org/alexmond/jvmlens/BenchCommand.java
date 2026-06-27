package org.alexmond.jvmlens;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The no-JMH bench harness: run an ordinary class's {@code main(String[])} in a
 * warmup→timed loop, capture a JFR (with {@code settings=profile}) over <em>just</em> the
 * timed phase, and summarize it. Removes the driver every consumer project otherwise
 * hand-rolls (load → warm up N → time M → emit JFR) when it has no JMH module to lean on.
 * Field-finding #53 item 5.
 *
 * <p>
 * The workload runs in this JVM (reflective {@code main} call), so an in-process
 * {@link Recording} captures it directly — no attach. Each {@code main} invocation is one
 * iteration; warmup invocations run before the recording starts so JIT/classload churn
 * stays out of the steady-state signal.
 */
@Component
@Command(name = "bench", mixinStandardHelpOptions = true,
		description = "Run a workload's main(String[]) in a warmup→timed loop, capture a JFR over the timed "
				+ "phase, and summarize it — the no-JMH bench harness for ordinary apps.")
public class BenchCommand implements Callable<Integer> {

	@Option(names = "--main", required = true, paramLabel = "<class>",
			description = "Fully-qualified class whose main(String[]) is one iteration of the workload.")
	String mainClass;

	@Option(names = { "--classpath", "--cp" }, paramLabel = "<cp>",
			description = "Extra classpath to load the workload from (entries separated by the platform path separator).")
	String classpath;

	@Option(names = { "-w", "--warmup" }, paramLabel = "<n>",
			description = "Warmup iterations run before recording starts (default: ${DEFAULT-VALUE}).")
	int warmup = 10;

	@Option(names = { "-i", "--iters" }, paramLabel = "<n>",
			description = "Timed iterations captured into the recording (default: ${DEFAULT-VALUE}).")
	int iters = 50;

	@Option(names = { "-s", "--settings" }, paramLabel = "<settings>",
			description = "JFR configuration: profile or default (default: ${DEFAULT-VALUE}).")
	String settings = "profile";

	@Option(names = "--jfr", paramLabel = "<file>",
			description = "Write (and keep) the captured recording here. Default: a temp file, deleted after.")
	Path jfr;

	@Option(names = "--no-analyze", description = "Only capture the JFR (requires --jfr); skip printing the summary.")
	boolean noAnalyze;

	@Parameters(paramLabel = "<args>",
			description = "Arguments passed to the workload's main on every iteration (use -- to separate).")
	String[] workloadArgs = new String[0];

	@Mixin
	OutputOptions output;

	@Override
	public Integer call() throws Exception {
		if (warmup < 0) {
			System.err.println("jvmlens: --warmup must not be negative: " + warmup);
			return 2;
		}
		if (iters <= 0) {
			System.err.println("jvmlens: --iters must be positive: " + iters);
			return 2;
		}
		if (noAnalyze && jfr == null) {
			System.err.println("jvmlens: --no-analyze needs --jfr (nothing would be produced otherwise)");
			return 2;
		}
		ClassLoader loader;
		Method main;
		try {
			loader = workloadLoader();
			main = Class.forName(mainClass, true, loader).getMethod("main", String[].class);
		}
		catch (ReflectiveOperationException | MalformedURLException ex) {
			System.err.println("jvmlens: cannot load --main " + mainClass + ": " + ex);
			return 2;
		}

		Path recording = (jfr != null) ? jfr : Files.createTempFile("jvmlens-bench", ".jfr");
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(loader);
		long elapsedNanos = 0;
		boolean ok = false;
		try (Recording rec = new Recording(Configuration.getConfiguration(settings))) {
			for (int i = 0; i < warmup; i++) {
				main.invoke(null, (Object) workloadArgs);
			}
			rec.start();
			long start = System.nanoTime();
			for (int i = 0; i < iters; i++) {
				main.invoke(null, (Object) workloadArgs);
			}
			elapsedNanos = System.nanoTime() - start;
			rec.stop();
			rec.dump(recording);
			ok = true;
		}
		catch (ReflectiveOperationException ex) {
			Throwable cause = (ex instanceof InvocationTargetException ite) ? ite.getCause() : ex;
			System.err.println("jvmlens: workload failed: " + cause);
		}
		finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
		if (!ok) {
			if (jfr == null) {
				Files.deleteIfExists(recording);
			}
			return 3;
		}

		double totalMs = elapsedNanos / 1_000_000.0;
		System.err.printf(Locale.ROOT, "jvmlens: bench %s — %d iters in %.1f ms (%.3f ms/iter)%n", mainClass, iters,
				totalMs, totalMs / iters);
		if (jfr != null) {
			System.err.println("jvmlens: recording kept at " + jfr);
		}
		try {
			if (!noAnalyze) {
				System.out.print(Summarizer.summarize(recording, output.format, output.scope(), output.report));
			}
		}
		finally {
			if (jfr == null) {
				Files.deleteIfExists(recording);
			}
		}
		return 0;
	}

	private ClassLoader workloadLoader() throws MalformedURLException {
		ClassLoader context = Thread.currentThread().getContextClassLoader();
		return (classpath == null || classpath.isBlank()) ? context : new URLClassLoader(toUrls(classpath), context);
	}

	private static URL[] toUrls(String cp) throws MalformedURLException {
		List<URL> urls = new ArrayList<>();
		for (String entry : cp.split(File.pathSeparator)) {
			if (!entry.isBlank()) {
				urls.add(new File(entry).toURI().toURL());
			}
		}
		return urls.toArray(new URL[0]);
	}

}
