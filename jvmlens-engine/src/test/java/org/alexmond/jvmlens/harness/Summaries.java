package org.alexmond.jvmlens.harness;

import java.util.ArrayList;
import java.util.List;

import org.alexmond.jvmlens.ProfileSummary;
import org.alexmond.jvmlens.ProfileSummary.Ranked;
import org.alexmond.jvmlens.ProfileSummary.Section;

/**
 * A terse builder for {@link ProfileSummary} fixtures, so a rule-detection case reads as
 * "a scenario with pathology X" rather than a 17-argument record literal. Most detectors
 * ({@code FixHints}, {@code suspectedCause}, the diff/trend hedges) operate on the model
 * record, so a hand-built summary is the fastest, most deterministic fixture — no JFR
 * recording needed. Only fields a case actually sets diverge from empty/zero defaults.
 *
 * @see RuleDetectionHarness
 */
public final class Summaries {

	private Summaries() {
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Fluent {@link ProfileSummary} builder; unset fields default to empty/zero. */
	public static final class Builder {

		private String source = "test.jfr";

		private long execSamples = 1000;

		private long gcPauseMillis;

		private final List<Ranked> hotPaths = new ArrayList<>();

		private final List<Ranked> hotLeaves = new ArrayList<>();

		private final List<Ranked> allocSites = new ArrayList<>();

		private final List<Ranked> locks = new ArrayList<>();

		private final List<Section> sections = new ArrayList<>();

		private String appPackage = "";

		public Builder execSamples(long n) {
			this.execSamples = n;
			return this;
		}

		public Builder gcPauseMillis(long ms) {
			this.gcPauseMillis = ms;
			return this;
		}

		public Builder hotPath(String name, double share, long count, String stack) {
			this.hotPaths.add(new Ranked(name, share, count, stack));
			return this;
		}

		public Builder allocSite(String name, double share, long count, String stack) {
			this.allocSites.add(new Ranked(name, share, count, stack));
			return this;
		}

		public Builder lock(String name, double share, long count, String stack) {
			this.locks.add(new Ranked(name, share, count, stack));
			return this;
		}

		public Builder appPackage(String pkg) {
			this.appPackage = pkg;
			return this;
		}

		/**
		 * Add one row to an extended {@link Section} (creating the section on first use).
		 */
		public Builder row(String key, String title, String unit, String rowName, double share, long count,
				String teaser) {
			Section existing = null;
			for (Section s : this.sections) {
				if (s.key().equals(key)) {
					existing = s;
					break;
				}
			}
			Ranked row = new Ranked(rowName, share, count, teaser);
			if (existing == null) {
				List<Ranked> rows = new ArrayList<>();
				rows.add(row);
				this.sections.add(new Section(key, title, unit, true, rows));
			}
			else {
				List<Ranked> rows = new ArrayList<>(existing.rows());
				rows.add(row);
				this.sections.remove(existing);
				this.sections.add(new Section(key, title, unit, true, rows));
			}
			return this;
		}

		/**
		 * Add a {@code db} (SQL) row: {@code shape}, total {@code ms}, and its teaser.
		 */
		public Builder db(String shape, long ms, String teaser) {
			return row("db", "Top SQL (by total time, sanitized)", "ms", shape, 0.5, ms, teaser);
		}

		public ProfileSummary build() {
			return new ProfileSummary(this.source, this.execSamples, 0, 0, 0, this.gcPauseMillis, this.hotPaths,
					this.hotLeaves, this.allocSites, List.of(), this.locks, List.of(), "", this.appPackage,
					this.sections, 0, 0);
		}

	}

}
