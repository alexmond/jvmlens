package org.alexmond.jvmlens;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class TrendCommandTest {

	private static final String LINES = """
			{"t":1000,"exec":1000,"hot":"com.example.Svc.run","hotShare":0.9,"hotCount":900,"gcPauses":1,"gcMs":10,"allocBytes":1000,"alloc":"com.example.Svc.alloc","oldObjects":2,"lock":"","lockMs":0,"cause":"c","ioMs":0,"pinnedMs":0}
			{"t":2000,"exec":1000,"hot":"com.example.Svc.run","hotShare":0.9,"hotCount":900,"gcPauses":1,"gcMs":80,"allocBytes":1000,"alloc":"com.example.Svc.alloc","oldObjects":40,"lock":"","lockMs":0,"cause":"c","ioMs":0,"pinnedMs":0}
			{"t":3000,"exec":1000,"hot":"com.example.Svc.run","hotShare":0.9,"hotCount":900,"gcPauses":1,"gcMs":160,"allocBytes":1000,"alloc":"com.example.Svc.alloc","oldObjects":90,"lock":"","lockMs":0,"cause":"c","ioMs":0,"pinnedMs":0}
			""";

	private static String run(Path file, String... args) {
		PrintStream originalOut = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		String[] full = new String[args.length + 1];
		System.arraycopy(args, 0, full, 0, args.length);
		full[args.length] = file.toString();
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			int rc = new CommandLine(new TrendCommand()).setCaseInsensitiveEnumValuesAllowed(true).execute(full);
			assertThat(rc).isZero();
		}
		finally {
			System.setOut(originalOut);
		}
		return captured.toString(StandardCharsets.UTF_8);
	}

	@Test
	void rejectsUnreadableFile() {
		int rc = new CommandLine(new TrendCommand()).execute("/no/such/history.jsonl");
		assertThat(rc).isEqualTo(2);
	}

	@Test
	void failsWhenNoParseableSamples() throws Exception {
		Path f = Files.createTempFile("jvmlens-empty", ".jsonl");
		Files.writeString(f, "\nnot json\n");
		try {
			int rc = new CommandLine(new TrendCommand()).execute(f.toString());
			assertThat(rc).isEqualTo(2);
		}
		finally {
			Files.deleteIfExists(f);
		}
	}

	@Test
	void rendersTrendDigestFromHistory() throws Exception {
		Path f = Files.createTempFile("jvmlens-history", ".jsonl");
		Files.writeString(f, LINES);
		try {
			String md = run(f);
			assertThat(md).contains("# JVM long-run trend");
			assertThat(md).contains("Window: 3 snapshots");
			assertThat(md).contains("Stable hot path `com.example.Svc.run`");
			assertThat(md).contains("possible* retention growth");
		}
		finally {
			Files.deleteIfExists(f);
		}
	}

	@Test
	void supportsJsonAndPromptFormats() throws Exception {
		Path f = Files.createTempFile("jvmlens-history", ".jsonl");
		Files.writeString(f, LINES);
		try {
			assertThat(run(f, "-f", "json")).startsWith("[").contains("\"oldObjects\":90");
			assertThat(run(f, "-f", "prompt")).startsWith("You are a JVM performance expert.")
				.contains("# JVM long-run trend");
		}
		finally {
			Files.deleteIfExists(f);
		}
	}

}
