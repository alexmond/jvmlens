package org.alexmond.jvmlens.mongo;

import java.lang.reflect.Method;
import java.util.List;

import org.alexmond.jvmlens.ProfileSummary.Section;
import org.alexmond.jvmlens.probe.CallSites;
import org.alexmond.jvmlens.probe.FailGuard;
import org.alexmond.jvmlens.probe.OpStore;

/**
 * Static facade over an {@link OpStore} for MongoDB operations (sync-driver
 * {@code MongoCollection} find / aggregate / insert / update / delete) captured by
 * {@code MongoAdvice}, rendered as the {@code mongo} extended section. Each op is
 * labelled {@code <collection>.<method>} (e.g. {@code users.find}, #147) so
 * per-collection N+1 stands out, and anchored to its application call-site (via
 * {@link CallSites}). Mirroring the SQL N+1 logic, a repeated
 * {@code find}/{@code aggregate} reads as a possible N+1 document fetch, and a repeated
 * single-document {@code insertOne}/{@code updateOne}/{@code deleteOne} as un-batched
 * writes (use {@code insertMany}/{@code bulkWrite}).
 */
public final class MongoStore {

	/** A read/write op repeated at least this many times is worth flagging. */
	private static final long N_PLUS_ONE_CALLS = 50;

	private static final OpStore STORE = new OpStore();

	private MongoStore() {
	}

	/**
	 * Record one MongoDB operation on {@code collection} (a {@code MongoCollection}
	 * instance, read reflectively for its name — no Mongo compile dependency) as
	 * {@code <collection>.<op>}; called from advice.
	 * @param collection the advised {@code MongoCollection} ({@code @This}), or null
	 * @param op the operation method name (find / insertOne / …)
	 * @param nanos the elapsed time
	 */
	public static void record(Object collection, String op, long nanos) {
		record(label(collection, op), nanos);
	}

	/**
	 * Record one MongoDB operation already labelled {@code <collection>.<op>} taking
	 * {@code nanos}.
	 */
	public static void record(String label, long nanos) {
		FailGuard.run("mongo", () -> {
			List<String> path = CallSites.capturePath();
			STORE.record(label, nanos, CallSites.site(path), CallSites.entryClass(path));
		});
	}

	/**
	 * The {@code <collection>.<op>} label: the collection name read reflectively off the
	 * advised instance's {@code getNamespace().getCollectionName()}. Fail-open — an
	 * unknown driver shape (or a null instance) degrades to the bare {@code op}, never
	 * throws.
	 */
	private static String label(Object collection, String op) {
		if (collection == null) {
			return op;
		}
		try {
			Method getNamespace = collection.getClass().getMethod("getNamespace");
			Object namespace = getNamespace.invoke(collection);
			Method getCollectionName = namespace.getClass().getMethod("getCollectionName");
			Object name = getCollectionName.invoke(namespace);
			return (name instanceof String s && !s.isEmpty()) ? s + "." + op : op;
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			return op;
		}
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
		// The label is <collection>.<op> (#147), so match the op suffix, not the whole
		// label — a per-collection prefix must not defeat the N+1 detection.
		String op = label.substring(label.lastIndexOf('.') + 1);
		if ("find".equals(op) || "aggregate".equals(op)) {
			return " — high call count, possible N+1 document fetch";
		}
		if ("insertOne".equals(op) || "updateOne".equals(op) || "deleteOne".equals(op) || "replaceOne".equals(op)) {
			return " — repeated single-document write, likely un-batched";
		}
		return "";
	}

}
