package org.alexmond.jvmlens;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ViaFramesTest {

	private static final String APP = "app.Evaluator.execute";

	@Test
	void namesTheDeepestLibraryFrameThatOwnsMostOfThePath() {
		// #162: 963 samples under the app frame; getReadMethod funnels 530 of them into
		// JDK leaves. Its callers own more but sit further from the work.
		ViaFrames via = new ViaFrames();
		for (int i = 0; i < 963; i++) {
			via.count(APP, "ognl.Ognl.getValue", 6);
		}
		for (int i = 0; i < 700; i++) {
			via.count(APP, "ognl.OgnlRuntime.getMethodValue", 3);
		}
		for (int i = 0; i < 530; i++) {
			via.count(APP, "ognl.OgnlRuntime.getReadMethod", 2);
		}
		assertThat(via.teaser(APP, 963, Map.of())).isEqualTo(" · mostly via ognl.OgnlRuntime.getReadMethod 530/963");
	}

	@Test
	void staysQuietWhenNoInnerFrameOwnsMostOfThePath() {
		ViaFrames via = new ViaFrames();
		for (int i = 0; i < 400; i++) {
			via.count(APP, "lib.A.a", 1);
			via.count(APP, "lib.B.b", 1);
		}
		assertThat(via.teaser(APP, 1000, Map.of())).isEmpty();
		assertThat(via.teaser("app.Other.path", 1000, Map.of())).isEmpty();
	}

	@Test
	void doesNotRepeatAFrameTheLeafListAlreadyShows() {
		// a library frame that is only ever the leaf is already in the leaf teaser
		ViaFrames via = new ViaFrames();
		for (int i = 0; i < 80; i++) {
			via.count(APP, "lib.Hot.spin", 0);
		}
		assertThat(via.teaser(APP, 100, Map.of("lib.Hot.spin", 80L))).isEmpty();
	}

	@Test
	void ignoresAPathTooSmallToJudge() {
		ViaFrames via = new ViaFrames();
		for (int i = 0; i < 5; i++) {
			via.count(APP, "lib.A.a", 1);
		}
		assertThat(via.teaser(APP, 5, Map.of())).isEmpty();
	}

}
