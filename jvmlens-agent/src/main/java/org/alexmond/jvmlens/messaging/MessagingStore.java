package org.alexmond.jvmlens.messaging;

import org.alexmond.jvmlens.probe.CallSites;
import org.alexmond.jvmlens.probe.FailGuard;
import java.util.List;

import org.alexmond.jvmlens.ProfileSummary.Section;
import org.alexmond.jvmlens.probe.OpStore;

/**
 * Static facade over an {@link OpStore} for messaging operations (Kafka / JMS producer
 * send + consumer poll/receive) captured by {@code MessagingAdvice}, rendered as the
 * {@code messaging} extended section. Each op is anchored to its application call-site,
 * and a high-volume {@code send} with material average latency is flagged as a
 * synchronous per-message send (Kafka's fast async sends stay well under the latency
 * gate).
 */
public final class MessagingStore {

	/** Below this many sends a synchronous-send pattern isn't worth flagging. */
	private static final long SYNC_SEND_MIN_CALLS = 50;

	/** Average send latency (ms) above which sends read as synchronous/per-message. */
	private static final double SYNC_SEND_MIN_AVG_MS = 2.0;

	private static final OpStore STORE = new OpStore();

	private MessagingStore() {
	}

	/**
	 * Record one messaging operation {@code op} taking {@code nanos}; called from advice.
	 */
	public static void record(String op, long nanos) {
		FailGuard.run("messaging", () -> {
			List<String> path = CallSites.capturePath();
			STORE.record(op, nanos, CallSites.site(path), CallSites.entryClass(path));
		});
	}

	/** Clear all captured operations and scope (used by tests). */
	public static void reset() {
		STORE.reset();
		CallSites.reset();
	}

	/** The captured operations as the {@code messaging} section, or empty. */
	public static List<Section> sections() {
		return STORE.sections("messaging", "Messaging operations (by total time)", MessagingStore::flag);
	}

	private static String flag(String label, long calls, long nanos) {
		double avgMs = (calls > 0) ? (nanos / 1_000_000.0 / calls) : 0;
		if (label.endsWith(".send") && calls >= SYNC_SEND_MIN_CALLS && avgMs >= SYNC_SEND_MIN_AVG_MS) {
			return " — synchronous per-message send";
		}
		return "";
	}

}
