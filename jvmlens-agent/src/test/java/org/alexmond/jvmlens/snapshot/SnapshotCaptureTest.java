package org.alexmond.jvmlens.snapshot;

import java.lang.instrument.Instrumentation;
import java.util.List;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotCaptureTest {

	@AfterEach
	void clear() {
		SnapshotStore.reset();
	}

	@Test
	void instrumentsMethodAndCapturesArguments() {
		Instrumentation instrumentation;
		try {
			instrumentation = ByteBuddyAgent.install();
		}
		catch (Throwable ex) {
			Assumptions.abort("self-attach / agent install unavailable here: " + ex.getMessage());
			return;
		}
		SnapshotCapture.install(instrumentation,
				List.of("org.alexmond.jvmlens.snapshot.SnapshotCaptureTest$Target#handle"));
		Target target = new Target();
		target.handle("aa", 3);
		target.handle("bbb", 7);
		String md = SnapshotStore.render();
		assertThat(md).contains("Target.handle");
		assertThat(md).contains("2 distinct");
		assertThat(md).contains("range 3..7");
	}

	/** Instrumentation target — its method's arguments should be captured. */
	public static final class Target {

		public int handle(String key, int n) {
			return key.length() + n;
		}

	}

}
