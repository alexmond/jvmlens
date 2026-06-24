package org.alexmond.jvmlens.snapshot;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice inlined at the entry of each instrumented method: it forwards the
 * call's arguments to {@link SnapshotStore}. Method arguments are always present (unlike
 * locals, which need {@code -g} debug info), so this is the robust first
 * variable-snapshot mode.
 */
public final class SnapshotAdvice {

	private SnapshotAdvice() {
	}

	@Advice.OnMethodEnter
	public static void enter(@Advice.Origin("#t.#m") String site, @Advice.AllArguments(readOnly = true) Object[] args) {
		SnapshotStore.record(site, args);
	}

}
