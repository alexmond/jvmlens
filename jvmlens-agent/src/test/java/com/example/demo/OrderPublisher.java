package com.example.demo;

import org.alexmond.jvmlens.messaging.MessagingStore;

/**
 * A stand-in messaging application component in a non-jvmlens package, so
 * {@link MessagingStore}'s call-site walk resolves to a realistic producer/consumer
 * frame.
 */
public final class OrderPublisher {

	private OrderPublisher() {
	}

	/**
	 * A producer send: the {@code MessagingStore.record} line is the captured call-site.
	 */
	public static void send(long nanos) {
		MessagingStore.record("org.acme.JmsTemplate.send", nanos);
	}

	/** A consumer poll (never a synchronous-send). */
	public static void poll(long nanos) {
		MessagingStore.record("org.acme.KafkaConsumer.poll", nanos);
	}

}
