package org.alexmond.jvmlens.messaging;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import org.alexmond.jvmlens.probe.AgentIgnores;

/**
 * Installs {@link MessagingAdvice} on the dominant Kafka / JMS / RabbitMQ producer-send
 * and consumer-poll/receive methods (matched by interface name so jvmlens needs no broker
 * dependency, and works for both {@code jakarta.jms} and {@code javax.jms}; ActiveMQ —
 * Classic on {@code javax.jms}, Artemis on {@code jakarta.jms} — is covered via JMS).
 * Each broker is hooked at its <em>lowest client level</em> — Kafka {@code Producer}, JMS
 * {@code MessageProducer}, RabbitMQ {@code Channel.basicPublish} — never the Spring
 * template above it ({@code KafkaTemplate}/{@code JmsTemplate}/{@code RabbitTemplate}),
 * so a Spring app is instrumented once, not double-counted. Tight scope: only the
 * send/poll/receive (and RabbitMQ basicPublish/basicGet) entry points, not the whole
 * client.
 */
public final class MessagingCapture {

	private MessagingCapture() {
	}

	/**
	 * Instrument messaging send/receive on the given instrumentation.
	 * @param instrumentation the JVM instrumentation (from the agent or a test harness)
	 */
	public static void install(Instrumentation instrumentation) {
		new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(AgentIgnores.base())
			.type(ElementMatchers.hasSuperType(ElementMatchers.named("org.apache.kafka.clients.producer.Producer")
				.or(ElementMatchers.named("org.apache.kafka.clients.consumer.Consumer"))
				.or(ElementMatchers.named("jakarta.jms.MessageProducer"))
				.or(ElementMatchers.named("javax.jms.MessageProducer"))
				.or(ElementMatchers.named("jakarta.jms.MessageConsumer"))
				.or(ElementMatchers.named("javax.jms.MessageConsumer"))
				.or(ElementMatchers.named("com.rabbitmq.client.Channel"))))
			.transform((b, td, classLoader, module,
					pd) -> b.visit(Advice.to(MessagingAdvice.class)
						.on(ElementMatchers.namedOneOf("send", "poll", "receive", "basicPublish", "basicGet")
							.and(ElementMatchers.isPublic()))))
			.installOn(instrumentation);
	}

}
