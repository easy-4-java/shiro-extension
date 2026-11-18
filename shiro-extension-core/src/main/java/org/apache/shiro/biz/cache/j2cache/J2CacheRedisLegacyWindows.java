package org.apache.shiro.biz.cache.j2cache;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import net.oschina.j2cache.Level2Cache;
import net.oschina.j2cache.util.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.biz.cache.redis.RedisCredentialRetryStore;
import org.apache.shiro.cache.CacheException;

/** Reads the persisted L2 counter and its Redis deadline while all retry clients are paused. */
final class J2CacheRedisLegacyWindows {

    private static final String DEADLINE = String.join("\n",
            "local ttl = redis.call('PTTL', KEYS[1])",
            "if ttl < 0 then return tostring(ttl) end",
            "local time = redis.call('TIME')",
            "local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)",
            "return string.format('%.0f', now + ttl)");

    private final CacheChannel channel;
    private final J2CacheConfig config;
    private final String region;
    private final boolean jedis;
    private final RedisCredentialRetryStore.ScriptExecutor scripts;

    J2CacheRedisLegacyWindows(CacheChannel channel, J2CacheConfig config, String region, boolean jedis,
                              RedisCredentialRetryStore.ScriptExecutor scripts) {
        this.channel = channel;
        this.config = config;
        this.region = region;
        this.jedis = jedis;
        this.scripts = scripts;
    }

    boolean migrate(String principal, RedisCredentialRetryStore destination) {
        Objects.requireNonNull(principal, "principal");
        String key = redisKey(principal);
        Level2Cache source = (Level2Cache) channel.getL2Provider().buildCache(region, null);
        byte[] bytes = source.getBytes(principal);
        if (Objects.isNull(bytes)) {
            return false;
        }
        long deadline = Long.parseLong(scripts.eval(DEADLINE, Collections.singletonList(key), Collections.emptyList()));
        if (deadline == -2) {
            return false;
        }
        try {
            Object value = SerializationUtils.deserialize(bytes);
            if (!(value instanceof AtomicInteger)) {
                throw new CacheException("J2Cache legacy retry entry must contain an AtomicInteger");
            }
            return destination.importFixedWindow(principal, ((AtomicInteger) value).get(), deadline == -1 ? 0 : deadline);
        } catch (IOException ex) {
            throw new CacheException("Unable to read the J2Cache legacy retry counter", ex);
        }
    }

    private String redisKey(String principal) {
        Properties redis = config.getL2CacheProperties();
        boolean hash = "hash".equalsIgnoreCase(redis.getProperty("storage", jedis ? "generic" : "hash"));
        // Jedis hash storage uses the platform charset and different whitespace rules.
        boolean jedisHash = jedis && hash;
        String normalizedRegion = jedisHash ? StringUtils.trim(region) : region;
        String name = StringUtils.isEmpty(normalizedRegion) ? "_" : region;
        String namespace = redis.getProperty("namespace");
        boolean hasNamespace = jedisHash ? StringUtils.isNotEmpty(namespace)
                : StringUtils.isNotEmpty(StringUtils.trim(namespace));
        String regionKey = hasNamespace ? namespace + ":" + name : name;
        if (jedisHash && !Arrays.equals(regionKey.getBytes(Charset.defaultCharset()),
                regionKey.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The legacy Jedis region key must use UTF-8 for scripted migration");
        }
        return hash ? regionKey : regionKey + ":" + principal;
    }
}
