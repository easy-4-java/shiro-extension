package org.apache.shiro.biz.cache.j2cache;

import java.util.List;
import java.util.Objects;
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
    private final J2CacheRedisLegacyWindows legacy;

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
        RedisCredentialRetryStore.ScriptExecutor scripts = (script, keys, args) -> eval(client, script, keys, args);
        this.delegate = new RedisCredentialRetryStore(scripts,
                namespace, principalKey, J2CacheRetryExpiry.resolve(channel, config, region, "generic"));
        this.legacy = new J2CacheRedisLegacyWindows(channel, config, region, true, scripts);
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
        RedisCredentialRetryStore.ScriptExecutor scripts = (script, keys, args) -> (String) cluster.eval(script, keys, args);
        this.delegate = new RedisCredentialRetryStore(scripts,
                namespace, principalKey, J2CacheRetryExpiry.resolve(channel, config, region, "generic"));
        this.legacy = new J2CacheRedisLegacyWindows(channel, config, region, true, scripts);
    }

    @Override
    public Attempt reserve(Object principal) {
        return delegate.reserve(principal);
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        delegate.clearIfCurrent(principal, windowId);
    }

    /**
     * Copies a persisted L2 counter into the dedicated retry namespace during an exclusive cutover.
     *
     * @param principal original string principal key in the J2Cache retry region
     * @return true when a live legacy window was imported into an empty destination
     */
    public boolean migrateLegacyWindow(String principal) {
        return legacy.migrate(principal, delegate);
    }

    private static RedisCacheProvider writableProvider(CacheChannel channel) {
        if (channel.getL2Provider() instanceof RedisCacheProvider
                && !(channel.getL2Provider() instanceof ReadonlyRedisCacheProvider)) {
            return (RedisCacheProvider) channel.getL2Provider();
        }
        throw new IllegalArgumentException("J2Cache retry state requires a writable Jedis provider");
    }

    private static String eval(RedisClient client, String script, List<String> keys, List<String> args) {
        try {
            BinaryJedisCommands connection = client.get();
            if (connection instanceof Jedis) {
                return (String) ((Jedis) connection).eval(script, keys, args);
            }
            if (connection instanceof ShardedJedis) {
                return (String) ((ShardedJedis) connection).getShard(keys.get(0)).eval(script, keys, args);
            }
            throw new CacheException("The J2Cache Redis connection does not expose script execution");
        } finally {
            client.release();
        }
    }
}
