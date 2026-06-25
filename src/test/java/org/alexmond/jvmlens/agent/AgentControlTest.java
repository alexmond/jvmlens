package org.alexmond.jvmlens.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.alexmond.jvmlens.RankLimits;

import static org.assertj.core.api.Assertions.assertThat;

class AgentControlTest {

	private final List<String> enableHook = new ArrayList<>();

	private AgentControl control() {
		return new AgentControl(true, "profile", 60, Set.of("deadlock", "db"), List.of("org.alexmond.jvmlens"),
				this.enableHook::add);
	}

	@AfterEach
	void resetLimits() {
		RankLimits.reset();
	}

	@Test
	void startStopTogglesRunning() {
		AgentControl c = control();
		assertThat(c.running()).isTrue();
		assertThat(c.apply("stop")).isEqualTo("stopped");
		assertThat(c.running()).isFalse();
		assertThat(c.apply("start")).isEqualTo("started");
		assertThat(c.running()).isTrue();
	}

	@Test
	void launchesPausedWhenConstructedNotRunning() {
		AgentControl c = new AgentControl(false, "profile", 60, Set.of("deadlock"), List.of(), null);
		assertThat(c.running()).isFalse();
	}

	@Test
	void enableInstallsFreshDimOnceAndDisableRemoves() {
		AgentControl c = control();
		assertThat(c.apply("enable web")).isEqualTo("enabled web");
		assertThat(c.enabled("web")).isTrue();
		assertThat(this.enableHook).containsExactly("web"); // fresh enable fires the hook
		c.apply("enable web"); // already enabled — no second install
		assertThat(this.enableHook).containsExactly("web");
		assertThat(c.apply("disable web")).isEqualTo("disabled web");
		assertThat(c.enabled("web")).isFalse();
		assertThat(c.apply("enable bogus")).startsWith("usage:");
	}

	@Test
	void clearAndDumpAreOneShotFlags() {
		AgentControl c = control();
		c.apply("clear");
		assertThat(c.takeClear()).isTrue();
		assertThat(c.takeClear()).isFalse();
		c.apply("dump");
		assertThat(c.takeDump()).isTrue();
		assertThat(c.takeDump()).isFalse();
	}

	@Test
	void settingsAndIntervalAreValidatedAndApplied() {
		AgentControl c = control();
		assertThat(c.apply("settings tracing")).startsWith("usage:");
		assertThat(c.apply("settings default")).isEqualTo("settings -> default");
		assertThat(c.settings()).isEqualTo("default");
		assertThat(c.takePendingSettings()).isEqualTo("default");
		assertThat(c.takePendingSettings()).isNull();
		assertThat(c.apply("interval 5")).isEqualTo("interval -> 5s");
		assertThat(c.interval()).isEqualTo(5);
		assertThat(c.apply("interval nope")).startsWith("usage:");
	}

	@Test
	void scopeAdjustsFilteringAndResets() {
		AgentControl c = control();
		c.apply("scope app com.example");
		c.apply("scope exclude com.noise");
		assertThat(c.scope().isApplication("com.example.Svc")).isTrue();
		assertThat(c.scope().isApplication("org.springframework.X")).isFalse(); // include-only
																				// mode
		c.apply("scope reset");
		// reset restores the initial exclude (jvmlens self) and clears includes
		assertThat(c.scope().isApplication("com.example.Svc")).isTrue();
		assertThat(c.scope().isApplication("org.alexmond.jvmlens.Summarizer")).isFalse();
	}

	@Test
	void topnQueriesSetsPerCategoryAndResets() {
		AgentControl c = control();
		assertThat(c.apply("topn")).contains("default=5");
		assertThat(c.apply("topn 10")).contains("default=10");
		assertThat(RankLimits.limit("io")).isEqualTo(10);
		assertThat(c.apply("topn db 3")).contains("db=3");
		assertThat(RankLimits.limit("db")).isEqualTo(3);
		assertThat(c.apply("topn perf 7")).contains("cpu=7"); // alias perf -> cpu
		assertThat(RankLimits.limit("cpu")).isEqualTo(7);
		assertThat(c.apply("topn reset")).contains("default=5");
		assertThat(RankLimits.limit("db")).isEqualTo(RankLimits.DEFAULT);
	}

	@Test
	void statusReflectsStateAndIgnoresBlankAndUnknown() {
		AgentControl c = control();
		assertThat(c.apply("")).isEmpty();
		assertThat(c.apply("# a comment")).isEmpty();
		assertThat(c.apply("wat")).startsWith("unknown command");
		String status = c.apply("status");
		assertThat(status).contains("running=true").contains("settings=profile").contains("topn[");
	}

}
