package org.alexmond.jvmlens;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jdk.jfr.consumer.RecordedFrame;

/**
 * Small confidence/teaser heuristics shared by the {@link Summarizer} rendering — the
 * leaf-distribution "where time actually goes" teaser (#53 item 3), the escape-analysis
 * "may be a false lever" check on allocation sites (#103), and the per-recording
 * breakdown for a merged JMH run (#153). Pure functions over already-aggregated data;
 * kept out of {@code Summarizer} so that class stays focused on the JFR reduction.
 */
final class Teasers {

	/**
	 * The synthetic "Top allocated types" row that collapses every type excluded by
	 * {@code -x} into one line (#128). The bytes stay accounted (folded, not dropped) so
	 * the app-attributable types stand out without hiding the total.
	 */
	static final String EXCLUDED_TYPES_LABEL = "«excluded types (-x), rolled up»";

	/**
	 * How many leaves to list per hot path in its leaf-distribution teaser (#53 item 3).
	 */
	static final int LEAF_TEASER_COUNT = 3;

	/**
	 * A path's top leaf must hold this share or the teaser is flagged diffuse (#53 item
	 * 3).
	 */
	static final double LEAF_CONFIDENCE = 0.20;

	/**
	 * Boxed primitives C2 can scalar-replace when non-escaping — see
	 * {@link #escapeProneType}.
	 */
	private static final Set<String> BOXED_PRIMITIVES = Set.of("java.lang.Integer", "java.lang.Long",
			"java.lang.Double", "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean",
			"java.lang.Float");

	/** A hidden class's per-JVM suffix: an optional linkage counter, then its address. */
	private static final Pattern HIDDEN_CLASS_ID = Pattern.compile("(?:\\$\\d+)?[/.]0x[0-9a-fA-F]+");

	/**
	 * A JDK dynamic proxy: an optional numbered module package, then {@code $Proxy<n>}.
	 */
	private static final Pattern JDK_PROXY = Pattern
		.compile("^(?:(jdk\\.proxy)\\d+(?=\\.\\$))?((?:.*\\.)?\\$Proxy)\\d+$");

	/** A ByteBuddy-generated subclass: a known marker, then a random suffix. */
	private static final Pattern BYTEBUDDY_SUFFIX = Pattern
		.compile("(\\$(?:MockitoMock|HibernateProxy|ByteBuddy))\\$\\w+$");

	/** A generated reflection accessor (JDK 17 and older): a per-JVM counter. */
	private static final Pattern REFLECT_ACCESSOR = Pattern
		.compile("^((?:jdk\\.internal|sun)\\.reflect\\.Generated\\w+?Accessor)\\d+$");

	/** The longest recording-supplied name printed in full. */
	static final int MAX_NAME = 240;

	/** How many hot paths to name in a per-recording breakdown teaser (#153). */
	private static final int PER_RECORDING_TEASER_PATHS = 3;

	private Teasers() {
	}

	/**
	 * A type name with its per-JVM hidden-class identity removed:
	 * {@code Foo$$Lambda.0x00000000963fbcc0} (and the older {@code Foo$$Lambda$14/0x…})
	 * become {@code Foo$$Lambda}. The address differs on every run, so without this the
	 * same lambda never matched across two recordings and diffed as a GONE + NEW pair
	 * (#161). Lambdas of one class share a row — the address never told them apart in a
	 * way a reader could use.
	 */
	static String stableName(String type) {
		String safe = safe(type);
		String name = (safe.indexOf("0x") < 0) ? safe : HIDDEN_CLASS_ID.matcher(safe).replaceAll("");
		return (name.indexOf('$') < 0 && !name.contains(".reflect.Generated")) ? name : stableGenerated(name);
	}

	/**
	 * Strip the per-run part of a generated class's name — the same GONE + NEW diff split
	 * as a lambda address, from other generators: a JDK proxy is numbered by creation
	 * order ({@code jdk.proxy1.$Proxy0}), a ByteBuddy subclass (Mockito mock, Hibernate
	 * proxy) carries a random suffix, a reflection accessor a counter. Each pattern is
	 * anchored to the generator's own shape, so a user class that merely looks similar
	 * ({@code com.acme.Proxy2}) is left alone.
	 */
	private static String stableGenerated(String name) {
		String out = name;
		if (out.contains("$Proxy")) {
			out = JDK_PROXY.matcher(out).replaceFirst("$1$2");
		}
		if (out.contains("Mock$") || out.contains("Proxy$") || out.contains("Buddy$")) {
			out = BYTEBUDDY_SUFFIX.matcher(out).replaceFirst("$1");
		}
		return out.contains(".reflect.Generated") ? REFLECT_ACCESSOR.matcher(out).replaceFirst("$1") : out;
	}

	/**
	 * A string read from a recording, made safe to print. A recording is untrusted input
	 * and its names land in text a model reads, usually inside a code span: a backtick
	 * would close the span, a line break would start a new markdown line, a bidirectional
	 * override would reorder what a human sees. Each of those becomes {@code ?}, and a
	 * name longer than {@value #MAX_NAME} characters is cut with an ellipsis. Spaces and
	 * non-ASCII letters stay — Kotlin test names and localized identifiers are
	 * legitimate. Returns the same instance when nothing needs changing.
	 */
	static String safe(String raw) {
		if (raw == null) {
			return null;
		}
		boolean clean = raw.length() <= MAX_NAME;
		for (int i = 0; clean && i < raw.length(); i++) {
			clean = !unsafe(raw.charAt(i));
		}
		if (clean) {
			return raw;
		}
		StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NAME) + 1);
		for (int i = 0; i < raw.length() && i < MAX_NAME; i++) {
			out.append(unsafe(raw.charAt(i)) ? '?' : raw.charAt(i));
		}
		return (raw.length() > MAX_NAME) ? out.append('…').toString() : out.toString();
	}

	private static boolean unsafe(char c) {
		return c == '`' || Character.isISOControl(c) || c == '\u2028' || c == '\u2029'
				|| (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069');
	}

	/** The {@code Type.method} row key for a frame, on a {@link #stableName}. */
	static String frameKey(RecordedFrame frame) {
		return stableName(frame.getMethod().getType().getName()) + "." + safe(frame.getMethod().getName());
	}

	/**
	 * The top {@value #LEAF_TEASER_COUNT} leaves of one hot path, formatted
	 * {@code leaf c/total · …} — where the path's time actually goes. Flagged
	 * {@code diffuse} when no single leaf holds {@value #LEAF_CONFIDENCE} of the path, so
	 * the reader doesn't chase a 2/168 frame (#53 item 3).
	 */
	static String leafBreakdown(Map<String, Long> byLeaf, long pathTotal) {
		List<Map.Entry<String, Long>> top = byLeaf.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(LEAF_TEASER_COUNT)
			.toList();
		String leaves = top.stream()
			.map((l) -> l.getKey() + " " + l.getValue() + "/" + pathTotal)
			.collect(java.util.stream.Collectors.joining(" · "));
		boolean diffuse = pathTotal <= 0 || top.isEmpty()
				|| (double) top.get(0).getValue() / pathTotal < LEAF_CONFIDENCE;
		return diffuse ? leaves + " ⚠ diffuse — no leaf >" + (int) (LEAF_CONFIDENCE * 100) + "% of path" : leaves;
	}

	/**
	 * Human-readable bytes (e.g. {@code 2.1 MB}) — the one formatter every renderer
	 * shares.
	 */
	static String humanBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = { "KB", "MB", "GB", "TB", "PB" };
		double value = bytes / 1024.0;
		int i = 0;
		while (value >= 1024 && i < units.length - 1) {
			value /= 1024;
			i++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, units[i]);
	}

	/** The most-weighted source line in a histogram, or 0 if none was recorded (#87). */
	static int dominantLine(Map<Integer, Long> hist) {
		if (hist == null || hist.isEmpty()) {
			return 0;
		}
		return hist.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
	}

	/**
	 * A non-escaping-allocation candidate C2 can scalar-replace — a boxed primitive or a
	 * captured lambda. A hot alloc site dominated by one may vanish in steady state, so
	 * its sampled bytes can be a false lever (#103); the renderer hedges it. Verify with
	 * {@code -prof gc}.
	 */
	static boolean escapeProneType(String type) {
		return type != null && (type.contains("$$Lambda") || BOXED_PRIMITIVES.contains(type));
	}

	/**
	 * A compact, readable type name: package stripped and JVM array descriptors decoded —
	 * {@code java.lang.String} → {@code String}, {@code [B} → {@code byte[]},
	 * {@code [Ljava.lang.String;} → {@code String[]}.
	 */
	static String simpleType(String type) {
		int dims = 0;
		while (dims < type.length() && type.charAt(dims) == '[') {
			dims++;
		}
		String base = (dims == 0) ? type : arrayBase(type.substring(dims));
		int dot = base.lastIndexOf('.');
		String simple = (dot >= 0) ? base.substring(dot + 1) : base;
		return simple + "[]".repeat(dims);
	}

	/**
	 * Fold every allocated type whose element package matches an explicit {@code -x}
	 * exclude into one rolled-up {@link #EXCLUDED_TYPES_LABEL} row, so app-attributable
	 * types aren't crowded out by embedded infrastructure (e.g. an in-process H2's
	 * MVStore types on a test capture). The bytes stay accounted, not dropped. The
	 * exclude prefixes are the same {@code -x} that already scopes the hot-path and
	 * allocation-site blocks; an empty exclude list leaves the map untouched (#128).
	 */
	static Map<String, Long> foldExcludedTypes(Map<String, Long> byType, List<String> excludes) {
		if (excludes.isEmpty()) {
			return byType;
		}
		Map<String, Long> out = new HashMap<>();
		long folded = 0;
		for (Map.Entry<String, Long> e : byType.entrySet()) {
			if (startsWithAnyPrefix(baseTypeName(e.getKey()), excludes)) {
				folded += e.getValue();
			}
			else {
				out.put(e.getKey(), e.getValue());
			}
		}
		if (folded > 0) {
			out.merge(EXCLUDED_TYPES_LABEL, folded, Long::sum);
		}
		return out;
	}

	/**
	 * The fully-qualified element type of a possibly-array type — the package is retained
	 * (unlike {@link #simpleType}) so an exclude prefix can match it. {@code
	 * [Lorg.h2.mvstore.Page$PageReference;} → {@code org.h2.mvstore.Page$PageReference};
	 * {@code [B} → {@code byte}; a scalar type is returned unchanged.
	 */
	private static String baseTypeName(String type) {
		int dims = 0;
		while (dims < type.length() && type.charAt(dims) == '[') {
			dims++;
		}
		return (dims == 0) ? type : arrayBase(type.substring(dims));
	}

	private static boolean startsWithAnyPrefix(String value, List<String> prefixes) {
		for (String p : prefixes) {
			if (value.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	/** The element type of a JVM array descriptor (e.g. {@code B} → {@code byte}). */
	private static String arrayBase(String descriptor) {
		return switch (descriptor.isEmpty() ? ' ' : descriptor.charAt(0)) {
			case 'B' -> "byte";
			case 'S' -> "short";
			case 'I' -> "int";
			case 'J' -> "long";
			case 'F' -> "float";
			case 'D' -> "double";
			case 'C' -> "char";
			case 'Z' -> "boolean";
			case 'L' -> descriptor.substring(1, descriptor.length() - 1);
			default -> descriptor;
		};
	}

	/**
	 * The top application hot paths of one recording as a compact {@code Method pct% · …}
	 * teaser, for a per-recording breakdown row (#153); {@code null} when the recording
	 * has no application execution samples.
	 * @param cpuByApp that recording's app-frame → sample-count histogram
	 * @param execSamples that recording's total execution samples
	 * @return the teaser, or {@code null}
	 */
	static String topHotPaths(Map<String, Long> cpuByApp, long execSamples) {
		if (cpuByApp.isEmpty() || execSamples == 0) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		cpuByApp.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(PER_RECORDING_TEASER_PATHS)
			.forEach((en) -> parts
				.add(String.format(Locale.ROOT, "%s %.0f%%", en.getKey(), 100.0 * en.getValue() / execSamples)));
		return String.join(" · ", parts);
	}

	/**
	 * The per-recording breakdown section (#153): one row per source {@code .jfr}, ranked
	 * by execution samples, showing its share of the merged total and its dominant hot
	 * paths as the teaser — the index that says which recording a merged hot path came
	 * from. File names that collide (JMH's per-fork {@code profile.jfr}) are
	 * disambiguated by their parent directory.
	 * @param perFile one {@link PerRecording} per source file
	 * @return the ranked section
	 */
	static ProfileSummary.Section perRecordingSection(List<PerRecording> perFile) {
		long total = perFile.stream().mapToLong(PerRecording::execSamples).sum();
		Map<Path, String> labels = perRecordingLabels(perFile);
		perFile.sort(Comparator.comparingLong(PerRecording::execSamples).reversed());
		List<ProfileSummary.Ranked> rows = new ArrayList<>();
		for (PerRecording pr : perFile) {
			double share = (total > 0) ? (double) pr.execSamples() / total : 0;
			rows.add(new ProfileSummary.Ranked(labels.get(pr.file()), share, pr.execSamples(), pr.hotPaths()));
		}
		return new ProfileSummary.Section("per-recording", "Per-recording breakdown", "samples", false, rows);
	}

	/**
	 * A distinguishing label per recording: {@code name}, or {@code parent/name} when
	 * names collide.
	 */
	private static Map<Path, String> perRecordingLabels(List<PerRecording> perFile) {
		Map<String, Long> nameCounts = new HashMap<>();
		for (PerRecording pr : perFile) {
			nameCounts.merge(String.valueOf(pr.file().getFileName()), 1L, Long::sum);
		}
		Map<Path, String> labels = new HashMap<>();
		for (PerRecording pr : perFile) {
			Path f = pr.file();
			String name = String.valueOf(f.getFileName());
			boolean collides = nameCounts.get(name) > 1 && f.getParent() != null;
			labels.put(f, collides ? f.getParent().getFileName() + "/" + name : name);
		}
		return labels;
	}

	/**
	 * One source recording's execution-sample count and dominant hot-path teaser (#153).
	 */
	record PerRecording(Path file, long execSamples, String hotPaths) {
	}

}
