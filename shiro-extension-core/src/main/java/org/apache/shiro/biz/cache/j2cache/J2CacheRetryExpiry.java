package org.apache.shiro.biz.cache.j2cache;

import java.time.Duration;
import java.util.UUID;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;

/** Resolves the Redis L2 expiry J2Cache applies when writing through a region. */
final class J2CacheRetryExpiry {

    private J2CacheRetryExpiry() {
    }

    static Duration resolve(CacheChannel channel, J2CacheConfig config, String region, String defaultStorage) {
        String storage = config.getL2CacheProperties().getProperty("storage", defaultStorage);
        if (!config.isSyncTtlToRedis() || "hash".equalsIgnoreCase(storage)) {
            return Duration.ZERO;
        }

        return Duration.ofSeconds(Math.max(0L, regionSeconds(channel, region)));
    }

    static long regionSeconds(CacheChannel channel, String region) {
        // Resolve the region through the channel so defaults and its existing listener are applied.
        channel.check(region, "__shiro_retry_region_probe__" + UUID.randomUUID());
        return channel.regions().stream()
                .filter(candidate -> region.equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("J2Cache retry region TTL is unavailable"))
                .getTtl();
    }
}
