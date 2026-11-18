package org.apache.shiro.biz.cache.j2cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class J2CacheLettuceCredentialRetryStoreIntegrationTest {

    private static final String REGION = "credential-retry";
    private final String namespace = "test:j2cache-lettuce-retry:" + UUID.randomUUID();
    private int port;

    @Before
    public void requireIsolatedRedis() {
        String configured = System.getProperty("shiro.redis.test.port");
        assumeTrue("Supply an isolated Redis port", Objects.nonNull(configured));
        port = Integer.parseInt(configured);
    }

    @Test
    public void independentConnectionsPreserveTheSyncedRegionDeadline() throws Exception {
        J2CacheConfig config = config(true, "generic");
        RedisClient client = client();
        try (CacheChannel channel = J2CacheTestChannel.open(config); StatefulRedisConnection<String, String> first = client.connect();
             StatefulRedisConnection<String, String> second = client.connect()) {
            var a = store(channel, config, first);
            var b = store(channel, config, second);
            Attempt initial = a.reserve("principal");
            long deadline = expiry(first);
            assertTrue(deadline > 0L);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                var left = workers.submit(() -> a.reserve("principal"));
                var right = workers.submit(() -> b.reserve("principal"));
                Attempt leftAttempt = left.get(5, TimeUnit.SECONDS);
                Attempt rightAttempt = right.get(5, TimeUnit.SECONDS);
                assertNotEquals(leftAttempt.getCount(), rightAttempt.getCount());
                assertEquals(5, leftAttempt.getCount() + rightAttempt.getCount());
                assertEquals(initial.getWindowId(), leftAttempt.getWindowId());
                assertEquals(initial.getWindowId(), rightAttempt.getWindowId());
            } finally {
                workers.shutdownNow();
            }
            assertEquals(deadline, expiry(second));
            long remaining = deadline - System.currentTimeMillis();
            if (remaining >= 0) {
                Thread.sleep(remaining + 40);
            }
            Attempt next = b.reserve("principal");
            assertEquals(1, next.getCount());
            assertNotEquals(initial.getWindowId(), next.getWindowId());
            a.clearIfCurrent("principal", initial.getWindowId());
            assertEquals(2, b.reserve("principal").getCount());
        } finally {
            client.shutdown();
        }
    }

    @Test
    public void disabledSynchronizationAndDefaultHashStorageStayPersistent() {
        RedisClient client = client();
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            for (J2CacheConfig config : new J2CacheConfig[] {config(false, "generic"), config(true, null)}) {
                try (CacheChannel channel = J2CacheTestChannel.open(config)) {
                    var store = store(channel, config, connection);
                    Attempt initial = store.reserve("principal");
                    assertEquals(-1L, expiry(connection));
                    store.clearIfCurrent("principal", initial.getWindowId());
                    assertEquals(-2L, expiry(connection));
                }
            }
        } finally {
            client.shutdown();
        }
    }

    private J2CacheLettuceCredentialRetryStore store(CacheChannel channel, J2CacheConfig config,
                                                   StatefulRedisConnection<String, String> connection) {
        return new J2CacheLettuceCredentialRetryStore(channel, config, REGION, namespace, Object::toString,
                connection.sync());
    }

    private long expiry(StatefulRedisConnection<String, String> connection) {
        return connection.sync().eval("return redis.call('PEXPIRETIME', KEYS[1])",
                ScriptOutputType.INTEGER, namespace + ":principal");
    }

    private RedisClient client() {
        return RedisClient.create("redis://127.0.0.1:" + port);
    }

    private J2CacheConfig config(boolean syncTtl, String storage) {
        J2CacheConfig config = new J2CacheConfig();
        config.setL1CacheName("caffeine");
        config.setL2CacheName("lettuce");
        config.setSyncTtlToRedis(syncTtl);
        config.getL1CacheProperties().setProperty("region." + REGION, "100, 2s");
        config.getL2CacheProperties().setProperty("hosts", "127.0.0.1:" + port);
        config.getL2CacheProperties().setProperty("password", "");
        config.getL2CacheProperties().setProperty("namespace", namespace + ":legacy");
        if (Objects.nonNull(storage)) {
            config.getL2CacheProperties().setProperty("storage", storage);
        }
        return config;
    }
}
