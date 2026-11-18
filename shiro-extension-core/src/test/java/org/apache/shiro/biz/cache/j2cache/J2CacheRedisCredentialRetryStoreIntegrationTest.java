package org.apache.shiro.biz.cache.j2cache;

import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import net.oschina.j2cache.Level2Cache;
import net.oschina.j2cache.util.SerializationUtils;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class J2CacheRedisCredentialRetryStoreIntegrationTest {

    private static final String REGION = "credential-retry";
    private final String namespace = "test:j2cache-retry:" + UUID.randomUUID();
    private int port;

    @Before
    public void requireIsolatedRedis() {
        String configured = System.getProperty("shiro.redis.test.port");
        assumeTrue("Supply an isolated Redis port", Objects.nonNull(configured));
        port = Integer.parseInt(configured);
    }

    @Test
    public void migratesBothL2LayoutsUsingPersistedCountsAndDeadlines() throws Exception {
        SerializationUtils.init("java", new Properties());
        try (Jedis redis = new Jedis("127.0.0.1", port)) {
            for (String storage : new String[] {"generic", "hash"}) {
                J2CacheConfig config = config(true, storage);
                try (CacheChannel channel = J2CacheTestChannel.open(config)) {
                    channel.set(REGION, "principal", new AtomicInteger(1));
                    Level2Cache source = (Level2Cache) channel.getL2Provider().buildCache(REGION, null);
                    source.put("principal", new AtomicInteger(3), 8L);
                    assertEquals(1, ((AtomicInteger) channel.get(REGION, "principal").getValue()).get());
                    String legacyKey = namespace + ":legacy:" + REGION + ("hash".equals(storage) ? "" : ":principal");
                    long deadline = (Long) redis.eval("return redis.call('PEXPIRETIME', KEYS[1])", 1, legacyKey);
                    J2CacheRedisCredentialRetryStore store = store(channel, config);
                    assertTrue(store.migrateLegacyWindow("principal"));
                    assertFalse(store.migrateLegacyWindow("principal"));
                    assertFalse(store.migrateLegacyWindow("missing"));
                    assertEquals(deadline, expiry(redis));
                    Attempt next = store.reserve("principal");
                    assertEquals(4, next.getCount());
                    assertEquals(deadline, expiry(redis));
                    assertEquals(3, ((AtomicInteger) source.get("principal")).get());
                    store.clearIfCurrent("principal", next.getWindowId());
                }
            }
        }
    }

    @Test
    public void twoChannelsShareCountsAndPreserveTheSyncedRegionDeadline() throws Exception {
        J2CacheConfig config = config(true, "generic");
        try (CacheChannel first = J2CacheTestChannel.open(config); CacheChannel second = J2CacheTestChannel.open(config);
             Jedis redis = new Jedis("127.0.0.1", port)) {
            var a = store(first, config);
            var b = store(second, config);
            Attempt initial = a.reserve("principal");
            long deadline = expiry(redis);
            assertTrue(deadline > 0L);
            assertTrue(redis.pttl(key()) <= 2_000L);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                var left = workers.submit(() -> a.reserve("principal"));
                var right = workers.submit(() -> b.reserve("principal"));
                Attempt secondAttempt = left.get(5, TimeUnit.SECONDS);
                Attempt thirdAttempt = right.get(5, TimeUnit.SECONDS);
                assertEquals(5, secondAttempt.getCount() + thirdAttempt.getCount());
                assertEquals(initial.getWindowId(), secondAttempt.getWindowId());
                assertEquals(initial.getWindowId(), thirdAttempt.getWindowId());
            } finally {
                workers.shutdownNow();
            }
            assertEquals(deadline, expiry(redis));
            long remaining = deadline - System.currentTimeMillis();
            if (remaining >= 0) {
                Thread.sleep(remaining + 40);
            }
            Attempt next = b.reserve("principal");
            assertEquals(1, next.getCount());
            assertNotEquals(initial.getWindowId(), next.getWindowId());
            a.clearIfCurrent("principal", initial.getWindowId());
            assertEquals(2, b.reserve("principal").getCount());
        }
    }

    @Test
    public void retainsPersistenceWhenTtlSyncIsDisabledOrRedisUsesHashStorage() {
        try (Jedis redis = new Jedis("127.0.0.1", port)) {
            for (J2CacheConfig config : new J2CacheConfig[] {config(false, "generic"), config(true, "hash")}) {
                try (CacheChannel channel = J2CacheTestChannel.open(config)) {
                    var store = store(channel, config);
                    Attempt initial = store.reserve("principal");
                    assertEquals(-1L, expiry(redis));
                    store.clearIfCurrent("principal", initial.getWindowId());
                    assertEquals(-2L, expiry(redis));
                }
            }
        }
    }

    private J2CacheRedisCredentialRetryStore store(CacheChannel channel, J2CacheConfig config) {
        return new J2CacheRedisCredentialRetryStore(channel, config, REGION, namespace, Object::toString);
    }

    private long expiry(Jedis redis) {
        return (Long) redis.eval("return redis.call('PEXPIRETIME', KEYS[1])", 1, key());
    }

    private String key() {
        return namespace + ":principal";
    }

    private J2CacheConfig config(boolean syncTtl, String storage) {
        J2CacheConfig config = new J2CacheConfig();
        config.setL1CacheName("caffeine");
        config.setL2CacheName("redis");
        config.setSyncTtlToRedis(syncTtl);
        config.getL1CacheProperties().setProperty("region." + REGION, "100, 2s");
        config.getL2CacheProperties().setProperty("hosts", "127.0.0.1:" + port);
        config.getL2CacheProperties().setProperty("namespace", namespace + ":legacy");
        config.getL2CacheProperties().setProperty("storage", storage);
        return config;
    }
}
