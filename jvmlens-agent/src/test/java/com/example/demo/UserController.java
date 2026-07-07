package com.example.demo;

import org.alexmond.jvmlens.web.WebStore;

/**
 * A stand-in "application" HTTP handler in a non-jvmlens package, so {@link WebStore}'s
 * call-site walk resolves to a realistic handler frame (as it would in a real target).
 */
public final class UserController {

	private UserController() {
	}

	/**
	 * Handles a request: the {@code WebStore.record} line below is the captured
	 * call-site.
	 */
	public static void handle(int status) {
		WebStore.record("GET", "/api/users/42", status, 3_000_000L);
	}

}
