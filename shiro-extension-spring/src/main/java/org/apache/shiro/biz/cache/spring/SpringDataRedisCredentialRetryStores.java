package org.apache.shiro.biz.cache.spring;

import java.nio.ByteBuffer;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.util.ByteUtils;

/** Creates retry stores for Spring Data Redis 2.7 cache regions. */
public final class SpringDataRedisCredentialRetryStores {

    private static final StringRedisSerializer STRINGS = StringRedisSerializer.UTF_8;
    private static final String SNAPSHOT_LEGACY = String.join("\n",
            "local value = redis.call('GET', KEYS[1])",
            "if not value then",
            "  return {}",
            "end",
            "local ttl = redis.call('PTTL', KEYS[1])",
            "if ttl == -2 then",
            "  return {}",
            "end",
            "if ttl == -1 then",
            "  return {value, 0}",
            "end",
            "local time = redis.call('TIME')",
            "local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)",
            "return {value, now + ttl}");

    private SpringDataRedisCredentialRetryStores() {
    }

    /**
     * Uses the cache's configured TTL and dedicated retry keys in the same Redis database.
     *
     * @param cache Spring Data Redis cache whose expiry policy is used
     * @param connectionFactory connection factory used by that Redis cache
     * @param namespace dedicated retry namespace shared by application instances
     * @param principalKey stable principal encoding shared by application instances
     * @return an atomic Redis retry store
     */
    public static CredentialRetryStore fromCache(Cache cache, RedisConnectionFactory connectionFactory,
                                                 String namespace, Function<Object, String> principalKey) {
        return createStore(unwrap(cache), connectionFactory, namespace, principalKey);
    }

    /**
     * Copies one legacy Spring Redis counter during an exclusive cache cutover.
     * Complete the import before either old or new matchers resume using the region.
     *
     * @param cache existing Spring Redis retry cache
     * @param connectionFactory connection factory used by the source and destination
     * @param namespace distinct destination namespace used by the new retry store
     * @param principalKey destination principal encoding used by the new retry store
     * @param principal original string principal in the source region
     * @return true when the provider copied a live counter into an empty destination key
     */
    public static boolean migrateLegacyWindow(Cache cache, RedisConnectionFactory connectionFactory,
                                              String namespace, Function<Object, String> principalKey,
                                              String principal) {
        RedisCache source = unwrap(cache);
        RedisCredentialRetryStore destination = createStore(source, connectionFactory, namespace, principalKey);
        RedisCacheConfiguration configuration = source.getCacheConfiguration();
        Objects.requireNonNull(principal, "principal");
        String sourceKey = configuration.usePrefix()
                ? configuration.getKeyPrefixFor(source.getName()) + principal : principal;
        byte[] serializedKey = ByteUtils.getBytes(configuration.getKeySerializationPair().write(sourceKey));
        List<?> snapshot;
        try (RedisConnection connection = connectionFactory.getConnection()) {
            snapshot = connection.scriptingCommands().eval(
                    STRINGS.serialize(SNAPSHOT_LEGACY), ReturnType.MULTI, 1, serializedKey);
        }
        if (snapshot.isEmpty()) {
            return false;
        }
        Object value = configuration.getValueSerializationPair().read(ByteBuffer.wrap((byte[]) snapshot.get(0)));
        if (!(value instanceof AtomicInteger)) {
            throw new IllegalStateException("Spring Redis legacy retry entry must contain an AtomicInteger");
        }
        return destination.importFixedWindow(principal, ((AtomicInteger) value).get(), (Long) snapshot.get(1));
    }

    private static RedisCache unwrap(Cache cache) {
        Cache target = cache;
        while (target instanceof TransactionAwareCacheDecorator) {
            target = ((TransactionAwareCacheDecorator) target).getTargetCache();
        }
        if (!(target instanceof RedisCache)) {
            throw new IllegalArgumentException("Spring retry cache must be backed by Spring Data Redis");
        }
        return (RedisCache) target;
    }

    private static RedisCredentialRetryStore createStore(RedisCache redisCache, RedisConnectionFactory connectionFactory,
                                                          String namespace, Function<Object, String> principalKey) {
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        return new RedisCredentialRetryStore(
                (script, keys, args) -> eval(connectionFactory, script, keys, args),
                namespace, principalKey, redisCache.getCacheConfiguration().getTtl());
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
