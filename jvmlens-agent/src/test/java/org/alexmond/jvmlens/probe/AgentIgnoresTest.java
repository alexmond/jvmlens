package org.alexmond.jvmlens.probe;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentIgnoresTest {

	private static boolean ignored(Class<?> c) {
		return AgentIgnores.base().matches(TypeDescription.ForLoadedType.of(c));
	}

	@Test
	void ignoresJvmlensByteBuddyAndSyntheticTypes() {
		assertThat(ignored(AgentIgnores.class)).isTrue(); // org.alexmond.jvmlens.*
		assertThat(ignored(ByteBuddy.class)).isTrue(); // net.bytebuddy.*
		Runnable lambda = () -> {
		};
		assertThat(ignored(lambda.getClass())).isTrue(); // synthetic (the EMF-SOE class
															// shape)
	}

	@Test
	void doesNotIgnoreOrdinaryAppOrLibraryTypes() {
		assertThat(ignored(String.class)).isFalse();
		assertThat(ignored(java.util.ArrayList.class)).isFalse();
	}

}
