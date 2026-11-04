package org.alexmond.jvmlens;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.alexmond.jvmlens.ProfileSummary.Lineage;
import org.alexmond.jvmlens.ProfileSummary.Ranked;

/**
 * Recognises a hot path that <em>moved</em> rather than appeared (#190). An
 * extract-method refactor moves work from caller {@code A} into a new callee {@code B}:
 * the diff then shows {@code B} as {@code NEW} with {@code A} down by about the same —
 * which reads as a new cost, though nothing got slower.
 *
 * <p>
 * A {@code NEW} hot path {@code B} is a move from {@code A} only when both hold:
 * <ul>
 * <li><strong>Call link</strong> — {@code A} is {@code B}'s nearest application caller in
 * at least {@value #LINKED} of {@code B}'s samples (a shared callee alone links nothing:
 * {@code HashMap.get} is everyone's callee).</li>
 * <li><strong>Same callee</strong> — one frame {@code B} calls into accounts for samples
 * {@code A} lost under that same frame: the smaller of {@code A}'s drop and {@code B}'s
 * count, both inclusive. Inclusive, not by leaf: the sampled leaf of an inlined chain
 * changes with each JIT compile (#190 on JDK 21), the outer callee does not.</li>
 * </ul>
 * When that overlap is at least {@value #MOVED} of {@code B} it is called a move; at
 * least {@value #POSSIBLY} (counting {@code B}'s own self time against {@code A}'s lost
 * self time, the extract-pure-computation case) it is hedged as "possibly moved". Below
 * that, nothing is said. Annotation only — the row and its rank stay.
 */
final class HotPathMoves {

	/**
	 * {@code A} must be {@code B}'s nearest application caller in this share of samples.
	 */
	static final double LINKED = 0.5;

	/** Same-callee overlap (share of {@code B}) for a confident "moved". */
	static final double MOVED = 0.5;

	/** Any overlap (share of {@code B}) for a hedged "possibly moved". */
	static final double POSSIBLY = 0.25;

	private HotPathMoves() {
	}

	/**
	 * The moves between two hot-path lists, keyed by the new path's name.
	 * @param before the baseline hot paths (ideally the full distribution)
	 * @param after the new hot paths
	 * @return new path → move; empty when rows carry no {@link Lineage}
	 */
	static Map<String, Move> find(List<Ranked> before, List<Ranked> after) {
		Map<String, Ranked> b = byName(before);
		Map<String, Ranked> a = byName(after);
		Map<String, Move> moves = new LinkedHashMap<>();
		for (Ranked row : after) {
			if (b.containsKey(row.name()) || row.lineage() == null) {
				continue;
			}
			Move m = match(row, b, a);
			if (m != null) {
				moves.put(row.name(), m);
			}
		}
		return moves;
	}

	/**
	 * Per-row diff notes: each new path's "moved from", and each caller's "moved into".
	 */
	static Map<String, String> notes(List<Ranked> before, List<Ranked> after) {
		Map<String, String> notes = new LinkedHashMap<>();
		for (Move m : find(before, after).values()) {
			notes.merge(m.to(), m.toNote(), String::concat);
			notes.merge(m.from(), m.fromNote(), String::concat);
		}
		return notes;
	}

	private static Move match(Ranked target, Map<String, Ranked> before, Map<String, Ranked> after) {
		String from = dominant(target.lineage().callers());
		if (from == null || target.lineage().callers().get(from) < LINKED * target.count()) {
			return null;
		}
		Ranked was = before.get(from);
		if (was == null || was.lineage() == null) {
			return null;
		}
		Ranked now = after.get(from);
		if (now != null && now.count() >= was.count()) {
			return null; // the caller did not shrink — nothing left it
		}
		Map<String, Long> lost = lost(was, now, from, target.name());
		long self = Math.min(target.lineage().callees().getOrDefault(target.name(), 0L),
				lost.getOrDefault(target.name(), 0L));
		String callee = null;
		long best = 0;
		for (Map.Entry<String, Long> e : target.lineage().callees().entrySet()) {
			if (e.getKey().equals(target.name())) {
				continue;
			}
			long overlap = Math.min(e.getValue(), lost.getOrDefault(e.getKey(), 0L));
			// inclusive counts nest (HashMap.get ⊇ getNode), so take the best one frame —
			// summing would count a sample once per frame; ties go to the name, for a
			// stable note
			if (overlap > best || (overlap == best && overlap > 0 && e.getKey().compareTo(callee) < 0)) {
				best = overlap;
				callee = e.getKey();
			}
		}
		boolean confident = best >= MOVED * target.count();
		if (!confident && best + self < POSSIBLY * target.count()) {
			return null;
		}
		long calleeLost = (callee != null) ? lost.get(callee) : 0;
		return new Move(from, target.name(), callee, calleeLost, best + self, confident);
	}

	/**
	 * Samples the caller lost per callee frame (inclusive). The caller's own self time is
	 * keyed under the new path's name, so an extracted pure computation — {@code A}'s
	 * self time becoming {@code B}'s — compares like with like.
	 */
	private static Map<String, Long> lost(Ranked was, Ranked now, String from, String to) {
		Map<String, Long> lost = new LinkedHashMap<>(was.lineage().callees());
		if (now != null && now.lineage() != null) {
			now.lineage().callees().forEach((frame, n) -> lost.merge(frame, -n, Long::sum));
		}
		Long selfLost = lost.remove(from);
		if (selfLost != null) {
			lost.merge(to, selfLost, Long::sum);
		}
		lost.replaceAll((frame, n) -> Math.max(0, n));
		return lost;
	}

	private static String dominant(Map<String, Long> counts) {
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	private static Map<String, Ranked> byName(List<Ranked> rows) {
		Map<String, Ranked> m = new LinkedHashMap<>();
		rows.forEach((r) -> m.putIfAbsent(r.name(), r));
		return m;
	}

	/**
	 * One recognised move.
	 *
	 * @param from the caller the work left
	 * @param to the new path it landed in
	 * @param callee the callee frame carrying the overlap ({@code null} if only self time
	 * matched)
	 * @param calleeLost how many samples under {@code callee} the caller lost
	 * @param overlap {@code B}'s samples explained by what the caller lost
	 * @param confident a same-callee move, not just a plausible one
	 */
	record Move(String from, String to, String callee, long calleeLost, long overlap, boolean confident) {

		/** The note for the new path's row. */
		String toNote() {
			if (this.confident) {
				return "  (moved from `" + this.from + "`: same callee `" + this.callee + "`, −" + this.calleeLost
						+ " under the caller — frames shifted, not new work)";
			}
			return "  (possibly moved from `" + this.from + "` — it calls this and lost " + this.overlap
					+ " matching samples; could still be new work)";
		}

		/** The note for the caller's row. */
		String fromNote() {
			return this.confident ? "  (" + this.overlap + " moved into `" + this.to + "`)"
					: "  (possibly partly moved into `" + this.to + "`)";
		}

	}

}
