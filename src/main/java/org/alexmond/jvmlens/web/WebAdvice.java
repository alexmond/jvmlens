package org.alexmond.jvmlens.web;

import net.bytebuddy.asm.Advice;

/**
 * Inlined ByteBuddy advice wrapped around {@code HttpServlet.service(request, response)}:
 * times the request and records {@code METHOD route} into {@link WebStore}. The request /
 * response are read <em>reflectively</em> (jvmlens has no servlet dependency, and this
 * works for both {@code jakarta.servlet} and {@code javax.servlet}); anything it can't
 * read degrades silently.
 */
public final class WebAdvice {

	private WebAdvice() {
	}

	/** Capture the start time before the request is handled. */
	@Advice.OnMethodEnter
	public static long enter() {
		return System.nanoTime();
	}

	/** Record the elapsed time against the request's method + route shape. */
	@Advice.OnMethodExit(onThrowable = Throwable.class)
	public static void exit(@Advice.Enter long start, @Advice.Argument(0) Object request,
			@Advice.Argument(1) Object response) {
		long elapsed = System.nanoTime() - start;
		String method = null;
		String uri = null;
		int status = 0;
		try {
			method = (String) request.getClass().getMethod("getMethod").invoke(request);
			uri = (String) request.getClass().getMethod("getRequestURI").invoke(request);
		}
		catch (Exception ignored) {
			// not an HTTP servlet request, or a non-standard impl — skip naming
		}
		try {
			status = (int) response.getClass().getMethod("getStatus").invoke(response);
		}
		catch (Exception ignored) {
			// response has no getStatus — leave status unknown
		}
		WebStore.record(method, uri, status, elapsed);
	}

}
