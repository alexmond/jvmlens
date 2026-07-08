package com.example.demo;

import org.alexmond.jvmlens.messaging.MessagingStore;

/**
 * A stand-in RabbitMQ publisher in a non-jvmlens package, recording the low-level
 * {@code Channel.basicPublish} label so {@link MessagingStore}'s call-site walk resolves
 * to a realistic app frame and the broker-agnostic synchronous-send flag is exercised for
 * RabbitMQ (not just Kafka/JMS {@code send}).
 */
public final class RabbitPublisher {

	private RabbitPublisher() {
	}

	/** A per-message publish (the low-level method RabbitMQ / Spring AMQP both reach). */
	public static void publish(long nanos) {
		MessagingStore.record("com.rabbitmq.client.impl.ChannelN.basicPublish", nanos);
	}

}
