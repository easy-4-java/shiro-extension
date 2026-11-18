package org.apache.shiro.biz.cache.spring;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.cache.redis.RedisCredentialRetryStore;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Creates retry stores for Spring Data Redis 3.5 cache regions. */
public final class SpringDataRedisCredentialRetryStores {

    private static final StringRedisSerializer STRINGS = StringRedisSerializer.UTF_8;

    private SpringDataRedisCredentialRetryStores() {
    }

    /**
     * Preserves the region's per-key TTL and optional time-to-idle behavior.
     *
     * @param cache Spring Data Redis cache whose expiry policy is used
     * @param connectionFactory connection factory used by that Redis cache
     * @param namespace dedicated retry namespace shared by application instances
     * @param principalKey stable principal encoding shared by application instances
     * @return an atomic Redis retry store
     */
    public static CredentialRetryStore fromCache(Cache cache, RedisConnectionFactory connectionFactory,
                                                 String namespace, Function<Object, String> principalKey) {
        Cache target = cache;
        while (target instanceof TransactionAwareCacheDecorator decorator) {
            target = decorator.getTargetCache();
        }
        if (!(target instanceof RedisCache redisCache)) {
            throw new IllegalArgumentException("Spring retry cache must be backed by Spring Data Redis");
        }
        Objects.requireNonNull(connectionFactory, "connectionFactory");

        RedisCacheConfiguration config = redisCache.getCacheConfiguration();
        TtlFunction ttl = config.getTtlFunction();
        Function<Object, Duration> accessExpiry = config.isTimeToIdleEnabled()
                ? principal -> ttl.getTimeToLive(principal, null) : principal -> Duration.ZERO;
        // The matcher historically inserted an AtomicInteger at zero before reserving its first attempt.
        return RedisCredentialRetryStore.withDynamicExpiry(
                (script, keys, args) -> eval(connectionFactory, script, keys, args),
                namespace, principalKey, principal -> ttl.getTimeToLive(principal, new AtomicInteger()), accessExpiry);
    }

    private static String eval(RedisConnectionFactory factory, String script, List<String> keys, List<String> args) {
        byte[][] arguments = Stream.concat(keys.stream(), args.stream()).map(STRINGS::serialize).toArray(byte[][]::new);
        try (RedisConnection connection = factory.getConnection()) {
            byte[] result = connection.scriptingCommands().eval(
                    STRINGS.serialize(script), ReturnType.VALUE, keys.size(), arguments);
            return STRINGS.deserialize(result);
        }
    }
}
