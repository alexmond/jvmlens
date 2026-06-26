package org.alexmond.jvmlens.snapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreTest {

	@AfterEach
	void clear() {
		SnapshotStore.reset();
	}

	@Test
	void emptyWhenNothingCaptured() {
		assertThat(SnapshotStore.render()).isEmpty();
	}

	@Test
	void summarizesArgumentDistributionAndRange() {
		SnapshotStore.record("com.ex.Svc.handle", new Object[] { "a", 5 });
		SnapshotStore.record("com.ex.Svc.handle", new Object[] { "b", 9 });
		SnapshotStore.record("com.ex.Svc.handle", new Object[] { "a", 7 });
		String md = SnapshotStore.render();
		assertThat(md).contains("## Variable snapshots");
		assertThat(md).contains("`com.ex.Svc.handle` — 3 calls");
		assertThat(md).contains("arg0: 2 distinct [a, b]");
		assertThat(md).contains("range 5..9");
	}

	@Test
	void countsNulls() {
		SnapshotStore.record("X.m", new Object[] { (Object) null });
		SnapshotStore.record("X.m", new Object[] { "v" });
		assertThat(SnapshotStore.render()).contains("1 null");
	}

}
