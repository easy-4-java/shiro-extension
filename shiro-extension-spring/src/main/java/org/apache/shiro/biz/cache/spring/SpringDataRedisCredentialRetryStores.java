package org.apache.shiro.biz.cache.spring;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.cache.redis.RedisCredentialRetryStore;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** Creates retry stores for Spring Data Redis 2.7 cache regions. */
public final class SpringDataRedisCredentialRetryStores {

    private static final StringRedisSerializer STRINGS = StringRedisSerializer.UTF_8;

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
        Cache target = cache;
        while (target instanceof TransactionAwareCacheDecorator) {
            target = ((TransactionAwareCacheDecorator) target).getTargetCache();
        }
        if (!(target instanceof RedisCache)) {
            throw new IllegalArgumentException("Spring retry cache must be backed by Spring Data Redis");
        }
        RedisCache redisCache = (RedisCache) target;
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
