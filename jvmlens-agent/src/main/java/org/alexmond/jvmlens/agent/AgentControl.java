package org.alexmond.jvmlens.agent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.function.Consumer;

import org.alexmond.jvmlens.RankLimits;
import org.alexmond.jvmlens.Scope;

/**
 * The agent's <em>in-flight</em> control state — what an operator can change at runtime
 * without restarting the JVM (the way commercial profilers offer live adjustment). A
 * {@code ControlChannel} feeds it command lines (from a watched control file); the agent
 * loop reads the resulting state each cycle.
 *
 * <p>
 * Commands: {@code start} / {@code stop} (pause/resume summaries), {@code clear} (reset
 * the window + instrumentation stores), {@code dump} (emit now), {@code enable <dim>} /
 * {@code disable <dim>}, {@code settings profile|default} (sampling density),
 * {@code interval <seconds>}, {@code scope app|exclude <prefix>} / {@code scope reset}
 * (application-frame filtering), and {@code status}. Launching with {@code paused} and
 * then {@code start} after warm-up is the clean answer to short cold runs profiling
 * startup.
 *
 * <p>
 * State is held in atomics / copy-on-write lists so the watcher thread and the agent loop
 * can touch it without locking.
 */
public final class AgentControl {

	/** Dimensions that {@code enable}/{@code disable} accept. */
	public static final Set<String> DIMENSIONS = Set.of("db", "web", "messaging", "cache", "mongo", "micrometer",
			"snapshot", "deadlock");

	/** {@code topn} category aliases → the engine/store category they map to. */
	private static final Map<String, String> TOPN_ALIASES = Map.of("perf", "cpu", "mem", "memory", "wait", "locks");

	private final AtomicBoolean running;

	private final AtomicBoolean clearRequested = new AtomicBoolean();

	private final AtomicBoolean dumpRequested = new AtomicBoolean();

	private final AtomicInteger interval;

	private final AtomicReference<String> settings;

	private final AtomicReference<String> pendingSettings = new AtomicReference<>();

	private final Set<String> enabled = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final List<String> include = new CopyOnWriteArrayList<>();

	private final List<String> exclude = new CopyOnWriteArrayList<>();

	private final List<String> initialExclude;

	private final Consumer<String> onEnable;

	/**
	 * @param running whether summaries emit at start ({@code false} = launch paused)
	 * @param settings the initial JFR config name
	 * @param interval the initial cadence in seconds
	 * @param enabledDims the dimensions on at launch
	 * @param initialExclude package prefixes always excluded (e.g. jvmlens's own)
	 * @param onEnable hook invoked when a dimension is freshly enabled (lazy install), or
	 * {@code null}
	 */
	public AgentControl(boolean running, String settings, int interval, Set<String> enabledDims,
			List<String> initialExclude, Consumer<String> onEnable) {
		this.running = new AtomicBoolean(running);
		this.settings = new AtomicReference<>(settings);
		this.interval = new AtomicInteger(interval);
		this.enabled.addAll(enabledDims);
		this.initialExclude = List.copyOf(initialExclude);
		this.exclude.addAll(initialExclude);
		this.onEnable = onEnable;
	}

	/**
	 * Apply one command line and return a human-readable result.
	 * @param line the command (blank lines and {@code #} comments are ignored)
	 * @return the outcome message
	 */
	public String apply(String line) {
		if (line == null || line.isBlank() || line.startsWith("#")) {
			return "";
		}
		String[] t = line.trim().split("\\s+");
		return switch (t[0]) {
			case "start" -> set(this.running, true, "started");
			case "stop" -> set(this.running, false, "stopped");
			case "clear" -> flag(this.clearRequested, "cleared");
			case "dump" -> flag(this.dumpRequested, "dump requested");
			case "enable" -> toggleDim(t, true);
			case "disable" -> toggleDim(t, false);
			case "settings" -> changeSettings(t);
			case "interval" -> changeInterval(t);
			case "scope" -> changeScope(t);
			case "topn" -> changeTopN(t);
			case "status" -> status();
			default -> "unknown command: " + t[0];
		};
	}

	private String toggleDim(String[] t, boolean on) {
		if (t.length < 2 || !DIMENSIONS.contains(t[1])) {
			return "usage: " + (on ? "enable" : "disable") + " <" + String.join("|", DIMENSIONS) + ">";
		}
		if (on) {
			boolean fresh = this.enabled.add(t[1]);
			if (fresh && this.onEnable != null) {
				this.onEnable.accept(t[1]);
			}
			return "enabled " + t[1];
		}
		this.enabled.remove(t[1]);
		return "disabled " + t[1];
	}

	private String changeSettings(String[] t) {
		if (t.length < 2 || !("profile".equals(t[1]) || "default".equals(t[1]))) {
			return "usage: settings <profile|default>";
		}
		this.settings.set(t[1]);
		this.pendingSettings.set(t[1]);
		return "settings -> " + t[1];
	}

	private String changeInterval(String[] t) {
		try {
			int n = Integer.parseInt(t[1]);
			if (n <= 0) {
				return "interval must be positive";
			}
			this.interval.set(n);
			return "interval -> " + n + "s";
		}
		catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
			return "usage: interval <seconds>";
		}
	}

	private String changeScope(String[] t) {
		if (t.length >= 2 && "reset".equals(t[1])) {
			this.include.clear();
			this.exclude.clear();
			this.exclude.addAll(this.initialExclude);
			return "scope reset";
		}
		if (t.length < 3 || !("app".equals(t[1]) || "exclude".equals(t[1]))) {
			return "usage: scope app <prefix> | scope exclude <prefix> | scope reset";
		}
		List<String> target = "app".equals(t[1]) ? this.include : this.exclude;
		if (!target.contains(t[2])) {
			target.add(t[2]);
		}
		return "scope " + t[1] + " += " + t[2];
	}

	/**
	 * {@code topn} — query (no args), reset, set all ({@code topn <n>}), or set a
	 * category ({@code topn <category> <n>}, where category is cpu/perf, memory/mem,
	 * locks/wait, io, pinning, or a plugin like db/web). Returns the resulting limits so
	 * a caller can read back "give me the top 5 SQL queries" et al.
	 */
	private String changeTopN(String[] t) {
		if (t.length < 2) {
			return "topn: " + RankLimits.describe();
		}
		if ("reset".equals(t[1])) {
			RankLimits.reset();
			return "topn reset; " + RankLimits.describe();
		}
		try {
			if (t.length == 2) {
				RankLimits.set("all", Integer.parseInt(t[1]));
			}
			else {
				RankLimits.set(TOPN_ALIASES.getOrDefault(t[1], t[1]), Integer.parseInt(t[2]));
			}
			return "topn: " + RankLimits.describe();
		}
		catch (NumberFormatException ex) {
			return "usage: topn [<category>] <n> | topn reset";
		}
	}

	private static String set(AtomicBoolean b, boolean value, String msg) {
		b.set(value);
		return msg;
	}

	private static String flag(AtomicBoolean b, String msg) {
		b.set(true);
		return msg;
	}

	/** Whether summaries are currently emitting. */
	public boolean running() {
		return this.running.get();
	}

	/** The current cadence in seconds. */
	public int interval() {
		return this.interval.get();
	}

	/** The current JFR config name. */
	public String settings() {
		return this.settings.get();
	}

	/** Whether a dimension is currently on. */
	public boolean enabled(String dim) {
		return this.enabled.contains(dim);
	}

	/** Take (and clear) a pending {@code clear} request. */
	public boolean takeClear() {
		return this.clearRequested.getAndSet(false);
	}

	/** Take (and clear) a pending {@code dump} request. */
	public boolean takeDump() {
		return this.dumpRequested.getAndSet(false);
	}

	/** Take (and clear) a pending settings change, or {@code null} if none. */
	public String takePendingSettings() {
		return this.pendingSettings.getAndSet(null);
	}

	/** The current application-frame {@link Scope} (filtering). */
	public Scope scope() {
		return Scope.of(List.copyOf(this.include), List.copyOf(this.exclude));
	}

	/** A compact rendering of the current control state. */
	public String status() {
		return "running=" + this.running.get() + " interval=" + this.interval.get() + "s settings="
				+ this.settings.get() + " enabled=" + this.enabled + " scope[app=" + this.include + " exclude="
				+ this.exclude + "] topn[" + RankLimits.describe() + "]";
	}

}
