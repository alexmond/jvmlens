package org.alexmond.jvmlens.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicFilesTest {

	@Test
	void replacesTheTargetAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
		Path target = dir.resolve("agent.md");
		Files.writeString(target, "old summary");

		AtomicFiles.writeString(target, "new summary");

		assertThat(Files.readString(target)).isEqualTo("new summary");
		try (var files = Files.list(dir)) {
			assertThat(files).containsExactly(target);
		}
	}

	@Test
	void createsTheTargetWhenAbsent(@TempDir Path dir) throws Exception {
		Path target = dir.resolve("agent.md");

		AtomicFiles.writeString(target, "first summary");

		assertThat(Files.readString(target)).isEqualTo("first summary");
	}

	@Test
	void neverWritesThroughALinkPlantedAtATempName(@TempDir Path dir) throws Exception {
		// out= often sits in a shared directory such as /tmp: another local user can
		// plant a link where the agent would put its temp file. The agent must not write
		// through it with the host's privileges.
		assumePosix();
		Path target = dir.resolve("agent.md");
		Files.writeString(target, "old summary");
		Path victim = Files.writeString(Files.createDirectory(dir.resolve("other")).resolve("victim"), "untouched");
		Files.createSymbolicLink(dir.resolve("agent.md.tmp"), victim);

		AtomicFiles.writeString(target, "new summary");

		assertThat(Files.readString(victim)).isEqualTo("untouched");
		assertThat(Files.isSymbolicLink(target)).isFalse();
		assertThat(Files.readString(target)).isEqualTo("new summary");
	}

	@Test
	void keepsThePermissionsOfAnExistingTarget(@TempDir Path dir) throws Exception {
		// a summary shared with a sidecar or group must stay as readable as it was
		assumePosix();
		Path target = dir.resolve("agent.md");
		Files.writeString(target, "old summary");
		Set<PosixFilePermission> shared = PosixFilePermissions.fromString("rw-r-----");
		Files.setPosixFilePermissions(target, shared);

		AtomicFiles.writeString(target, "new summary");

		assertThat(Files.getPosixFilePermissions(target)).isEqualTo(shared);
	}

	@Test
	void givesANewTargetThePermissionsAPlainWriteWould(@TempDir Path dir) throws Exception {
		// no wider and no narrower than the old in-place write: the process umask decides
		assumePosix();
		Path reference = Files.writeString(dir.resolve("reference.md"), "x");
		Path target = dir.resolve("agent.md");

		AtomicFiles.writeString(target, "first summary");

		assertThat(Files.getPosixFilePermissions(target)).isEqualTo(Files.getPosixFilePermissions(reference));
	}

	@Test
	void fallsBackToAnInPlaceWriteWhenNoTempFileCanBeCreated(@TempDir Path dir) throws Exception {
		// a read-only directory holding a writable summary: the rename route is closed,
		// yet the summary must still be published
		assumePosix();
		Path sub = Files.createDirectory(dir.resolve("ro"));
		Path target = Files.writeString(sub.resolve("agent.md"), "old summary");
		Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("r-x------"));
		try {
			assumeFalse(Files.isWritable(sub), "running as a user that ignores directory permissions");

			AtomicFiles.writeString(target, "fallback summary");

			assertThat(Files.readString(target)).isEqualTo("fallback summary");
		}
		finally {
			Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	void replacesALinkPlantedAtTheTargetInsteadOfWritingThroughIt(@TempDir Path dir) throws Exception {
		// another local user plants the summary path itself as a link before the agent
		// starts: the rename must replace the link, never write into what it points at
		assumePosix();
		Path victim = Files.writeString(Files.createDirectory(dir.resolve("other")).resolve("victim"), "untouched");
		Path target = Files.createSymbolicLink(dir.resolve("agent.md"), victim);

		AtomicFiles.writeString(target, "new summary");

		assertThat(Files.readString(victim)).isEqualTo("untouched");
		assertThat(Files.isSymbolicLink(target)).isFalse();
		assertThat(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)).isTrue();
		assertThat(Files.readString(target)).isEqualTo("new summary");
	}

	@Test
	void theFallbackRefusesToWriteThroughALinkAtTheTarget(@TempDir Path dir) throws Exception {
		// a read-only directory closes the rename route; the in-place fallback must still
		// not follow a link planted at the target, and says so instead of writing
		assumePosix();
		Path victim = Files.writeString(Files.createDirectory(dir.resolve("other")).resolve("victim"), "untouched");
		Path sub = Files.createDirectory(dir.resolve("ro"));
		Path target = Files.createSymbolicLink(sub.resolve("agent.md"), victim);
		Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("r-x------"));
		try {
			assumeFalse(Files.isWritable(sub), "running as a user that ignores directory permissions");

			assertThatIOException().isThrownBy(() -> AtomicFiles.writeString(target, "new summary"))
				.withMessageContaining("symbolic link");

			assertThat(Files.readString(victim)).isEqualTo("untouched");
		}
		finally {
			Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	void aPrivateTargetsNewContentIsNeverInAWiderFile(@TempDir Path dir) throws Exception {
		// the temp file must already be as private as the target when content goes in
		assumePosix();
		Path target = Files.writeString(dir.resolve("agent.md"), "old summary");
		Set<PosixFilePermission> owner = PosixFilePermissions.fromString("rw-------");
		Files.setPosixFilePermissions(target, owner);
		List<Set<PosixFilePermission>> seen = new ArrayList<>();

		AtomicFiles.writeString(target, "new summary", (Path tmp) -> seen.add(permissions(tmp)));

		assertThat(seen).containsExactly(owner);
		assertThat(Files.getPosixFilePermissions(target)).isEqualTo(owner);
		assertThat(Files.readString(target)).isEqualTo("new summary");
	}

	@Test
	void aTargetWiderThanTheUmaskIsWidenedBeforeContentGoesIn(@TempDir Path dir) throws Exception {
		// the umask can narrow the creation attribute; the temp is widened back to the
		// target's permissions before writing, so the rename does not narrow the summary
		assumePosix();
		Path target = Files.writeString(dir.resolve("agent.md"), "old summary");
		Set<PosixFilePermission> groupWritable = PosixFilePermissions.fromString("rw-rw----");
		Files.setPosixFilePermissions(target, groupWritable);
		List<Set<PosixFilePermission>> seen = new ArrayList<>();

		AtomicFiles.writeString(target, "new summary", (Path tmp) -> seen.add(permissions(tmp)));

		assertThat(seen).containsExactly(groupWritable);
		assertThat(Files.getPosixFilePermissions(target)).isEqualTo(groupWritable);
	}

	private static Set<PosixFilePermission> permissions(Path file) {
		try {
			return Files.getPosixFilePermissions(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void assumePosix() {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX file system only");
	}

}
