package org.alexmond.jvmlens.redis;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import org.alexmond.jvmlens.probe.AgentIgnores;

/**
 * Installs {@link RedisAdvice} on direct Redis command entry points — Lettuce's async
 * command impl (which the sync API also delegates to, since the sync API is a reflective
 * proxy that can't be instrumented directly) and Jedis's command interface. Matched by
 * name, so jvmlens needs no Redis dependency. Tight scope: only the common command
 * methods.
 *
 * <p>
 * The Lettuce type is matched on {@code io.lettuce.core.api.async.*} (the command
 * sub-interfaces), not just {@code RedisAsyncCommands}: the concrete
 * {@code RedisAsyncCommandsImpl} implements {@code RedisAsyncCommands} but the
 * {@code get} / {@code set} / … methods are actually <em>declared</em> in its superclass
 * {@code AbstractRedisAsyncCommands} (which implements the sub-interfaces), and ByteBuddy
 * only advises methods declared by a matched type — so matching just
 * {@code RedisAsyncCommands} instrumented nothing (#146).
 */
public final class RedisCapture {

	private RedisCapture() {
	}

	/**
	 * Instrument direct Redis commands on the given instrumentation.
	 * @param instrumentation the JVM instrumentation (from the agent or a test harness)
	 */
	public static void install(Instrumentation instrumentation) {
		new AgentBuilder.Default().disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.ignore(AgentIgnores.base())
			.type(ElementMatchers.hasSuperType(ElementMatchers.nameStartsWith("io.lettuce.core.api.async.")
				.or(ElementMatchers.named("redis.clients.jedis.commands.JedisCommands"))))
			.transform((b, td, classLoader, module,
					pd) -> b.visit(Advice.to(RedisAdvice.class)
						.on(ElementMatchers
							.namedOneOf("get", "set", "getex", "mget", "mset", "hget", "hgetall", "hmget", "hset",
									"lindex", "lpush", "rpush", "lrange", "zadd", "zscore", "zrange", "sadd",
									"sismember", "smembers", "incr", "decr", "expire", "del", "exists")
							.and(ElementMatchers.isPublic()))))
			.installOn(instrumentation);
	}

}
