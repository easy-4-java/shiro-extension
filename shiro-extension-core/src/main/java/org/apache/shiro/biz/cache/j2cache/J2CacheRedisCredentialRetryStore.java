package org.apache.shiro.biz.cache.j2cache;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import net.oschina.j2cache.redis.ReadonlyRedisCacheProvider;
import net.oschina.j2cache.redis.RedisCacheProvider;
import net.oschina.j2cache.redis.RedisClient;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.cache.redis.RedisCredentialRetryStore;
import org.apache.shiro.cache.CacheException;
import redis.clients.jedis.BinaryJedisCommands;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.ShardedJedis;

/**
 * Credential retry state in the Redis second-level store of a J2Cache channel.
 * Reservations go directly to Redis so each application observes the shared count.
 */
public final class J2CacheRedisCredentialRetryStore implements CredentialRetryStore {

    private final RedisCredentialRetryStore delegate;

    /**
     * @param channel channel using the writable Jedis provider
     * @param config the configuration used to create the channel
     * @param region existing retry region whose expiry policy is reused
     * @param namespace dedicated retry namespace shared by all instances
     * @param principalKey stable principal encoding shared by all instances
     */
    public J2CacheRedisCredentialRetryStore(CacheChannel channel, J2CacheConfig config, String region,
                                           String namespace, Function<Object, String> principalKey) {
        RedisCacheProvider provider = writableProvider(channel);
        if ("cluster".equals(config.getL2CacheProperties().getProperty("mode"))) {
            throw new IllegalArgumentException("J2Cache Cluster mode requires its native JedisCluster script client");
        }
        RedisClient client = provider.getRedisClient();
        this.delegate = new RedisCredentialRetryStore(
                (script, keys, args) -> eval(client, script, keys, args),
                namespace, principalKey, expiry(channel, config, region));
    }

    /**
     * @param channel channel using the writable Jedis Cluster provider
     * @param config the configuration used to create the channel
     * @param region existing retry region whose expiry policy is reused
     * @param namespace dedicated retry namespace shared by all instances
     * @param principalKey stable principal encoding shared by all instances
     * @param cluster caller-managed script client connected to the same Redis cluster
     */
    public J2CacheRedisCredentialRetryStore(CacheChannel channel, J2CacheConfig config, String region,
                                           String namespace, Function<Object, String> principalKey,
                                           JedisCluster cluster) {
        writableProvider(channel);
        Objects.requireNonNull(cluster, "cluster");
        this.delegate = new RedisCredentialRetryStore(
                (script, keys, args) -> (String) cluster.eval(script, keys, args),
                namespace, principalKey, expiry(channel, config, region));
    }

    @Override
    public Attempt reserve(Object principal) {
        return delegate.reserve(principal);
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        delegate.clearIfCurrent(principal, windowId);
    }

    private static RedisCacheProvider writableProvider(CacheChannel channel) {
        if (channel.getL2Provider() instanceof RedisCacheProvider provider
                && !(provider instanceof ReadonlyRedisCacheProvider)) {
            return provider;
        }
        throw new IllegalArgumentException("J2Cache retry state requires a writable Jedis provider");
    }

    private static Duration expiry(CacheChannel channel, J2CacheConfig config, String region) {
        if (!config.isSyncTtlToRedis()
                || "hash".equalsIgnoreCase(config.getL2CacheProperties().getProperty("storage"))) {
            return Duration.ZERO;
        }
        // Resolve the region through the channel so defaults and its existing listener are applied.
        channel.check(region, "__shiro_retry_region_probe__" + UUID.randomUUID());
        long seconds = channel.regions().stream()
                .filter(candidate -> region.equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("J2Cache retry region TTL is unavailable"))
                .getTtl();
        return Duration.ofSeconds(Math.max(0L, seconds));
    }

    private static String eval(RedisClient client, String script, List<String> keys, List<String> args) {
        try {
            BinaryJedisCommands connection = client.get();
            if (connection instanceof Jedis jedis) {
                return (String) jedis.eval(script, keys, args);
            }
            if (connection instanceof ShardedJedis sharded) {
                return (String) sharded.getShard(keys.get(0)).eval(script, keys, args);
            }
            throw new CacheException("The J2Cache Redis connection does not expose script execution");
        } finally {
            client.release();
        }
    }
}
