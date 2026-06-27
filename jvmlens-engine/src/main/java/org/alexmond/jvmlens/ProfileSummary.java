package org.alexmond.jvmlens;

import java.util.ArrayList;
import java.util.List;

/**
 * Render-agnostic result of summarizing a JFR recording. The {@link Summarizer} produces
 * one of these; the renderers in {@link Renderers} turn it into markdown, JSON, or an LLM
 * prompt. Keeping the structured form separate is what lets the (planned) MCP server
 * serve the same scoped data the CLI prints.
 *
 * @param source the recording file name
 * @param execSamples number of execution (CPU) samples seen
 * @param allocTypes distinct allocated types seen
 * @param oldObjects old-object (retained) samples seen
 * @param gcPauses number of GC pause phases
 * @param gcPauseMillis total GC pause time, milliseconds
 * @param hotPaths hottest application-attributed call sites, by sample share
 * @param hotLeaves hottest leaf methods (self-time, runtime included)
 * @param allocSites top application allocation sites, by estimated bytes
 * @param allocatedTypes top allocated types, by estimated bytes
 * @param locks lock contention by application method, by blocked time
 * @param monitors contended monitor classes, by blocked time
 * @param cause one-line heuristic cause
 * @param appPackage the recording's dominant application package (e.g.
 * {@code org.alexmond}), or {@code null} if none could be detected — a hint for what to
 * pass to {@code --app-package}
 * @param sections extended (beyond CPU/memory/wait) dimensions — external I/O,
 * virtual-thread pinning, and, later, web / db / messaging — each a self-describing
 * {@link Section} so new dimensions are additive rather than a signature change
 * @param allocBytes total estimated bytes allocated across the recording — the
 * <em>absolute</em> memory anchor, so a before→after diff isn't fooled by share alone
 * (optimizing shrinks the denominator; see field-finding #43)
 * @param allocSamples number of allocation samples seen — the confidence behind the
 * per-site byte splits: the total is reliable, but on a short trial the per-site shares
 * are statistically noisy (field-finding #50 item 3), so the renderer hedges when this is
 * low
 */
public record ProfileSummary(String source, long execSamples, int allocTypes, long oldObjects, long gcPauses,
		long gcPauseMillis, List<Ranked> hotPaths, List<Ranked> hotLeaves, List<Ranked> allocSites,
		List<Ranked> allocatedTypes, List<Ranked> locks, List<Ranked> monitors, String cause, String appPackage,
		List<Section> sections, long allocBytes, long allocSamples) {

	/**
	 * Back-compatible constructor for callers (and tests) predating the extended
	 * {@code sections}; defaults them to empty and
	 * {@code allocBytes}/{@code allocSamples} to 0.
	 */
	public ProfileSummary(String source, long execSamples, int allocTypes, long oldObjects, long gcPauses,
			long gcPauseMillis, List<Ranked> hotPaths, List<Ranked> hotLeaves, List<Ranked> allocSites,
			List<Ranked> allocatedTypes, List<Ranked> locks, List<Ranked> monitors, String cause, String appPackage) {
		this(source, execSamples, allocTypes, oldObjects, gcPauses, gcPauseMillis, hotPaths, hotLeaves, allocSites,
				allocatedTypes, locks, monitors, cause, appPackage, List.of(), 0L, 0L);
	}

	/**
	 * Back-compatible constructor for callers (and tests) predating {@code allocBytes};
	 * defaults it and {@code allocSamples} to 0.
	 */
	public ProfileSummary(String source, long execSamples, int allocTypes, long oldObjects, long gcPauses,
			long gcPauseMillis, List<Ranked> hotPaths, List<Ranked> hotLeaves, List<Ranked> allocSites,
			List<Ranked> allocatedTypes, List<Ranked> locks, List<Ranked> monitors, String cause, String appPackage,
			List<Section> sections) {
		this(source, execSamples, allocTypes, oldObjects, gcPauses, gcPauseMillis, hotPaths, hotLeaves, allocSites,
				allocatedTypes, locks, monitors, cause, appPackage, sections, 0L, 0L);
	}

	/**
	 * Back-compatible constructor for callers (and tests) predating {@code allocSamples};
	 * defaults it to 0.
	 */
	public ProfileSummary(String source, long execSamples, int allocTypes, long oldObjects, long gcPauses,
			long gcPauseMillis, List<Ranked> hotPaths, List<Ranked> hotLeaves, List<Ranked> allocSites,
			List<Ranked> allocatedTypes, List<Ranked> locks, List<Ranked> monitors, String cause, String appPackage,
			List<Section> sections, long allocBytes) {
		this(source, execSamples, allocTypes, oldObjects, gcPauses, gcPauseMillis, hotPaths, hotLeaves, allocSites,
				allocatedTypes, locks, monitors, cause, appPackage, sections, allocBytes, 0L);
	}

	/**
	 * A copy of this summary with {@code extra} extended sections appended — how an
	 * instrumentation front-end (e.g. the agent's SQL/web capture) folds its dimensions
	 * into the same render-agnostic structure the engine produced from JFR.
	 * @param extra sections to append (ignored if empty/null)
	 * @return this if {@code extra} is empty, else a copy with the sections merged
	 */
	public ProfileSummary withSections(List<Section> extra) {
		if (extra == null || extra.isEmpty()) {
			return this;
		}
		List<Section> merged = new ArrayList<>(this.sections);
		merged.addAll(extra);
		return new ProfileSummary(this.source, this.execSamples, this.allocTypes, this.oldObjects, this.gcPauses,
				this.gcPauseMillis, this.hotPaths, this.hotLeaves, this.allocSites, this.allocatedTypes, this.locks,
				this.monitors, this.cause, this.appPackage, List.copyOf(merged), this.allocBytes, this.allocSamples);
	}

	/**
	 * One ranked row.
	 *
	 * @param name the method or type name
	 * @param share fraction (0..1) of the relevant total this row accounts for
	 * @param count absolute weight behind the share (samples / bytes / nanos) — the hit
	 * rate that says whether the share is trustworthy or just one stray hit
	 * @param stack optional call-stack teaser ({@code null} when not applicable)
	 */
	public record Ranked(String name, double share, long count, String stack) {
	}

	/**
	 * One extended dimension's ranked section, self-describing so {@link Renderers} can
	 * render it generically and a report focus can select it by {@code key}.
	 *
	 * @param key the focus key (e.g. {@code io}, {@code pinning}, {@code db}) — matches a
	 * {@link Summarizer.Report} name
	 * @param title the section heading
	 * @param unit count unit for {@code formatCount} ({@code ms} / {@code bytes} / other)
	 * @param measured {@code true} for exact ([measured]) signal, {@code false} for
	 * sampled
	 * @param rows the ranked rows
	 */
	public record Section(String key, String title, String unit, boolean measured, List<Ranked> rows) {
	}

}
