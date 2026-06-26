package org.alexmond.jvmlens.deadlock;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlockDetectorTest {

	@Test
	void detectsARealTwoThreadDeadlock() throws Exception {
		// no deadlock yet
		assertThat(DeadlockDetector.detect()).isEmpty();

		Object lockA = new Object();
		Object lockB = new Object();
		CountDownLatch bothHoldFirst = new CountDownLatch(2);
		Thread t1 = new Thread(() -> grabThenGrab(lockA, lockB, bothHoldFirst), "jvmlens-deadlock-1");
		Thread t2 = new Thread(() -> grabThenGrab(lockB, lockA, bothHoldFirst), "jvmlens-deadlock-2");
		t1.setDaemon(true);
		t2.setDaemon(true);
		t1.start();
		t2.start();

		List<Section> sections = List.of();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			sections = DeadlockDetector.detect();
			if (!sections.isEmpty()) {
				break;
			}
			Thread.sleep(50);
		}

		assertThat(sections).hasSize(1);
		Section deadlock = sections.get(0);
		assertThat(deadlock.key()).isEqualTo("deadlock");
		assertThat(deadlock.measured()).isTrue();
		assertThat(deadlock.rows()).hasSize(2);
		assertThat(deadlock.rows()).extracting(Ranked::name)
			.containsExactlyInAnyOrder("jvmlens-deadlock-1", "jvmlens-deadlock-2");
		assertThat(deadlock.rows().get(0).stack()).startsWith("waiting on ").contains("held by");
	}

	private static void grabThenGrab(Object first, Object second, CountDownLatch ready) {
		synchronized (first) {
			ready.countDown();
			try {
				ready.await(); // ensure both threads hold their first lock before
								// reaching for the second
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			synchronized (second) {
				// unreachable: this is the deadlock
				second.hashCode();
			}
		}
	}

}
