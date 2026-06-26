package org.alexmond.jvmlens;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingsTest {

	@Test
	void expandReturnsASingleFileUnchanged() throws Exception {
		Path f = Files.createTempFile("rec", ".jfr");
		try {
			assertThat(Recordings.expand(f)).containsExactly(f);
		}
		finally {
			Files.deleteIfExists(f);
		}
	}

	@Test
	void expandFindsEveryJfrUnderADirectorySorted() throws Exception {
		Path dir = Files.createTempDirectory("jmh-run");
		Path a = Files.writeString(dir.resolve("a.jfr"), "");
		Path b = Files.writeString(dir.resolve("b.jfr"), "");
		Path fork = Files.createDirectory(dir.resolve("fork2"));
		Path c = Files.writeString(fork.resolve("profile.jfr"), "");
		Files.writeString(dir.resolve("notes.txt"), ""); // ignored — not .jfr
		try {
			assertThat(Recordings.expand(dir)).containsExactly(a, b, c);
		}
		finally {
			Files.deleteIfExists(c);
			Files.deleteIfExists(fork);
			Files.deleteIfExists(a);
			Files.deleteIfExists(b);
			Files.deleteIfExists(dir.resolve("notes.txt"));
			Files.deleteIfExists(dir);
		}
	}

	@Test
	void labelDisambiguatesCollidingFileNamesByParentDir() {
		// JMH names every fork's file profile.jfr — fall back to parent/name in a diff
		Path before = Path.of("/runs/before/profile.jfr");
		Path after = Path.of("/runs/after/profile.jfr");
		assertThat(Recordings.label(before, after)).isEqualTo("before/profile.jfr");
		assertThat(Recordings.label(after, before)).isEqualTo("after/profile.jfr");
		// distinct names keep the plain file name
		assertThat(Recordings.label(Path.of("x.jfr"), Path.of("y.jfr"))).isEqualTo("x.jfr");
		assertThat(Recordings.label(Path.of("only.jfr"), null)).isEqualTo("only.jfr");
	}

}
