package org.alexmond.jvmlens.mongo;

import java.util.List;

import org.alexmond.jvmlens.ProfileSummary.Section;
import org.alexmond.jvmlens.probe.CallSites;
import org.alexmond.jvmlens.probe.FailGuard;
import org.alexmond.jvmlens.probe.OpStore;

/**
 * Static facade over an {@link OpStore} for MongoDB operations (sync-driver
 * {@code MongoCollection} find / aggregate / insert / update / delete) captured by
 * {@code MongoAdvice}, rendered as the {@code mongo} extended section. Each op is
 * anchored to its application call-site (via {@link CallSites}), and — mirroring the SQL
 * N+1 logic — a repeated {@code find}/{@code aggregate} reads as a possible N+1 document
 * fetch, and a repeated single-document
 * {@code insertOne}/{@code updateOne}/{@code deleteOne} as un-batched writes (use
 * {@code insertMany}/{@code bulkWrite}).
 */
public final class MongoStore {

	/** A read/write op repeated at least this many times is worth flagging. */
	private static final long N_PLUS_ONE_CALLS = 50;

	private static final OpStore STORE = new OpStore();

	private MongoStore() {
	}

	/**
	 * Record one MongoDB operation {@code op} taking {@code nanos}; called from advice.
	 */
	public static void record(String op, long nanos) {
		FailGuard.run("mongo", () -> {
			List<String> path = CallSites.capturePath();
			STORE.record(op, nanos, CallSites.site(path), CallSites.entryClass(path));
		});
	}

	/** Clear all captured operations and scope (used by tests). */
	public static void reset() {
		STORE.reset();
		CallSites.reset();
	}

	/** The captured operations as the {@code mongo} section, or empty. */
	public static List<Section> sections() {
		return STORE.sections("mongo", "MongoDB operations (by total time)", MongoStore::flag);
	}

	private static String flag(String label, long calls, long nanos) {
		if (calls < N_PLUS_ONE_CALLS) {
			return "";
		}
		if ("find".equals(label) || "aggregate".equals(label)) {
			return " — high call count, possible N+1 document fetch";
		}
		if ("insertOne".equals(label) || "updateOne".equals(label) || "deleteOne".equals(label)
				|| "replaceOne".equals(label)) {
			return " — repeated single-document write, likely un-batched";
		}
		return "";
	}

}
