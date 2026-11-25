package org.alexmond.jvmlens.agent;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicFilesTest {

	@Test
	void replacesTheTargetAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
		Path target = dir.resolve("agent.md");
		Files.writeString(target, "old summary");

		AtomicFiles.writeString(target, "new summary");

		assertThat(Files.readString(target)).isEqualTo("new summary");
		assertThat(dir.resolve("agent.md.tmp")).doesNotExist();
	}

	@Test
	void createsTheTargetWhenAbsent(@TempDir Path dir) throws Exception {
		Path target = dir.resolve("agent.md");

		AtomicFiles.writeString(target, "first summary");

		assertThat(Files.readString(target)).isEqualTo("first summary");
	}

	@Test
	void fallsBackToAnInPlaceWriteWhenTheTempFileCannotBeUsed(@TempDir Path dir) throws Exception {
		// a non-empty directory squatting on the temp name: the temp write fails and the
		// clean-up cannot remove it, yet the summary must still be published
		Path target = dir.resolve("agent.md");
		Path squatter = Files.createDirectory(dir.resolve("agent.md.tmp"));
		Files.writeString(squatter.resolve("keep"), "x");

		AtomicFiles.writeString(target, "fallback summary");

		assertThat(Files.readString(target)).isEqualTo("fallback summary");
	}

}
