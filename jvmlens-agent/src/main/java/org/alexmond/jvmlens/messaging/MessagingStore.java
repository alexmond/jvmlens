package org.alexmond.jvmlens.messaging;

import org.alexmond.jvmlens.probe.FailGuard;
import java.util.List;

import org.alexmond.jvmlens.ProfileSummary.Section;
import org.alexmond.jvmlens.probe.OpStore;

/**
 * Static facade over an {@link OpStore} for messaging operations (Kafka / JMS producer
 * send + consumer poll/receive) captured by {@code MessagingAdvice}, rendered as the
 * {@code messaging} extended section.
 */
public final class MessagingStore {

	private static final OpStore STORE = new OpStore();

	private MessagingStore() {
	}

	/**
	 * Record one messaging operation {@code op} taking {@code nanos}; called from advice.
	 */
	public static void record(String op, long nanos) {
		FailGuard.run("messaging", () -> STORE.record(op, nanos));
	}

	/** Clear all captured operations (used by tests). */
	public static void reset() {
		STORE.reset();
	}

	/** The captured operations as the {@code messaging} section, or empty. */
	public static List<Section> sections() {
		return STORE.sections("messaging", "Messaging operations (by total time)");
	}

}
