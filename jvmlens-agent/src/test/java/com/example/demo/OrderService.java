package com.example.demo;

/**
 * An outer application caller of {@link OrderPublisher}, in a distinct class, so the
 * captured call-path has two app frames — {@code OrderPublisher} (the anchor) beneath
 * {@code OrderService} (the request entry). Exercises P2b's {@code ↳ under} marker and
 * {@link org.alexmond.jvmlens.probe.CallSites#entryClass}.
 */
public final class OrderService {

	private OrderService() {
	}

	/**
	 * Publish beneath this service, so {@code OrderService} is the outermost app frame.
	 */
	public static void publish(long nanos) {
		OrderPublisher.send(nanos);
	}

}
