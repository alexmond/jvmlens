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
 * at least {@value #LINKED} of {@code B}'s samples (a shared leaf alone links nothing:
 * {@code HashMap.get} is everyone's leaf).</li>
 * <li><strong>Same leaves</strong> — the leaf samples {@code A} lost cover {@code B}'s
 * leaf samples: per leaf, the smaller of {@code A}'s drop and {@code B}'s count.</li>
 * </ul>
 * When the same-leaf overlap is at least {@value #MOVED} of {@code B} it is called a
 * move; at least {@value #POSSIBLY} (counting {@code B}'s own self time against
 * {@code A}'s lost self time, the extract-pure-computation case) it is hedged as
 * "possibly moved". Below that, nothing is said. Annotation only — the row and its rank
 * stay.
 */
final class HotPathMoves {

	/**
	 * {@code A} must be {@code B}'s nearest application caller in this share of samples.
	 */
	static final double LINKED = 0.5;

	/** Same-leaf overlap (share of {@code B}) for a confident "moved". */
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
		Map<String, Long> lostByLeaf = lostByLeaf(was, now, from, target.name());
		long same = 0;
		long self = 0;
		String leaf = null;
		long leafOverlap = 0;
		for (Map.Entry<String, Long> e : target.lineage().leaves().entrySet()) {
			String key = leafKey(e.getKey());
			long overlap = Math.min(e.getValue(), lostByLeaf.getOrDefault(key, 0L));
			if (key.equals(target.name())) {
				self += overlap;
			}
			else {
				same += overlap;
				if (overlap > leafOverlap) {
					leafOverlap = overlap;
					leaf = e.getKey();
				}
			}
		}
		boolean confident = same >= MOVED * target.count();
		if (!confident && same + self < POSSIBLY * target.count()) {
			return null;
		}
		long leafLost = (leaf != null) ? lostByLeaf.get(leafKey(leaf)) : 0;
		return new Move(from, target.name(), leaf, leafLost, same + self, confident);
	}

	/**
	 * Samples the caller lost per leaf (without line). The caller's own self time is
	 * keyed under the new path's name, so an extracted pure computation — {@code A}'s
	 * self time becoming {@code B}'s — compares like with like.
	 */
	private static Map<String, Long> lostByLeaf(Ranked was, Ranked now, String from, String to) {
		Map<String, Long> lost = new LinkedHashMap<>();
		was.lineage().leaves().forEach((leaf, n) -> lost.merge(leafKey(leaf), n, Long::sum));
		if (now != null && now.lineage() != null) {
			now.lineage().leaves().forEach((leaf, n) -> lost.merge(leafKey(leaf), -n, Long::sum));
		}
		Long selfLost = lost.remove(from);
		if (selfLost != null) {
			lost.merge(to, selfLost, Long::sum);
		}
		lost.replaceAll((leaf, n) -> Math.max(0, n));
		return lost;
	}

	/** A leaf label without its {@code :line} suffix. */
	static String leafKey(String label) {
		int colon = label.lastIndexOf(':');
		if (colon > 0 && colon < label.length() - 1
				&& label.substring(colon + 1).chars().allMatch(Character::isDigit)) {
			return label.substring(0, colon);
		}
		return label;
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
	 * @param leaf the leaf carrying most of the overlap ({@code null} if only self time
	 * matched)
	 * @param leafLost how many samples of {@code leaf} the caller lost
	 * @param overlap {@code B}'s samples explained by what the caller lost
	 * @param confident a same-leaf move, not just a plausible one
	 */
	record Move(String from, String to, String leaf, long leafLost, long overlap, boolean confident) {

		/** The note for the new path's row. */
		String toNote() {
			if (this.confident) {
				return "  (moved from `" + this.from + "`: same leaf `" + this.leaf + "`, −" + this.leafLost
						+ " under the caller — frames shifted, not new work)";
			}
			return "  (possibly moved from `" + this.from + "` — it calls this and lost " + this.overlap
					+ " matching leaf samples; could still be new work)";
		}

		/** The note for the caller's row. */
		String fromNote() {
			return this.confident ? "  (" + this.overlap + " moved into `" + this.to + "`)"
					: "  (possibly partly moved into `" + this.to + "`)";
		}

	}

}
