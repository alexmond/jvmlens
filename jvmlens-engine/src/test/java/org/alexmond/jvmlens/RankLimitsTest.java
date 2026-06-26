package org.alexmond.jvmlens;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankLimitsTest {

	@AfterEach
	void reset() {
		RankLimits.reset();
	}

	@Test
	void defaultsToFiveWithNoOverrides() {
		assertThat(RankLimits.limit("db")).isEqualTo(RankLimits.DEFAULT);
		assertThat(RankLimits.describe()).isEqualTo("default=5");
	}

	@Test
	void categoryOverrideWinsOverAllWinsOverDefault() {
		RankLimits.set("all", 8);
		assertThat(RankLimits.limit("web")).isEqualTo(8); // all override
		RankLimits.set("web", 3);
		assertThat(RankLimits.limit("web")).isEqualTo(3); // category beats all
		assertThat(RankLimits.limit("db")).isEqualTo(8); // others still follow all
		assertThat(RankLimits.describe()).contains("default=8").contains("web=3");
	}

	@Test
	void clampsToAtLeastOne() {
		RankLimits.set("db", 0);
		assertThat(RankLimits.limit("db")).isEqualTo(1);
	}

}
