package org.apache.shiro.biz.cache.j2cache;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import net.oschina.j2cache.Level2Cache;
import net.oschina.j2cache.util.SerializationUtils;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.command.BinaryCommandFactory;
import net.rubyeye.xmemcached.transcoders.StringTranscoder;
import net.rubyeye.xmemcached.utils.AddrUtil;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class J2CacheMemcachedCredentialRetryStoreIntegrationTest {

    private static final String REGION = "credential-retry";
    private final String namespace = "test:memcached-retry:" + UUID.randomUUID();
    private String endpoint;
    private MemcachedClient firstClient;
    private MemcachedClient secondClient;

    @Before
    public void connect() throws Exception {
        String port = System.getProperty("shiro.memcached.test.port");
        assumeTrue("Supply an isolated Memcached port", Objects.nonNull(port));
        endpoint = "127.0.0.1:" + port;
        firstClient = client();
        secondClient = client();
    }

    @After
    public void disconnect() throws Exception {
        try {
            if (Objects.nonNull(secondClient)) {
                secondClient.shutdown();
            }
        } finally {
            if (Objects.nonNull(firstClient)) {
                firstClient.shutdown();
            }
        }
    }

    @Test
    public void reservesUniqueCountsAcrossChannelsAndClearsOnlyTheMatchingWindow() throws Exception {
        J2CacheConfig config = config(true, "0s");
        try (CacheChannel first = J2CacheTestChannel.open(config); CacheChannel second = J2CacheTestChannel.open(config)) {
            var a = store(first, config, firstClient);
            var b = store(second, config, secondClient);
            String principal = "shared principal 用户";
            ExecutorService threads = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Attempt>> requests = new ArrayList<>();
                for (int i = 0; i < 48; i++) {
                    var selected = i % 2 == 0 ? a : b;
                    requests.add(() -> selected.reserve(principal));
                }
                Set<Integer> counts = new HashSet<>();
                Set<String> windows = new HashSet<>();
                for (Future<Attempt> result : threads.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                    Attempt attempt = result.get();
                    assertTrue(counts.add(attempt.getCount()));
                    windows.add(attempt.getWindowId());
                }
                assertEquals(1, windows.size());
                assertEquals(48, counts.size());
                assertTrue(counts.contains(1));
                assertTrue(counts.contains(48));
                String initial = windows.iterator().next();
                a.clearIfCurrent(principal, initial);
                Attempt renewed = b.reserve(principal);
                assertEquals(1, renewed.getCount());
                assertNotEquals(initial, renewed.getWindowId());
                a.clearIfCurrent(principal, initial);
                assertEquals(2, b.reserve(principal).getCount());
            } finally {
                threads.shutdownNow();
            }
        }
    }

    @Test
    public void copiesTheLegacyCounterWithItsExactDeadlineAndPersistentZero() throws Exception {
        SerializationUtils.init("java", new Properties());
        J2CacheConfig config = config(true, "30s");
        config.getL2CacheProperties().setProperty("sanitizeKeys", "true");
        try (CacheChannel channel = J2CacheTestChannel.open(config)) {
            Level2Cache source = (Level2Cache) channel.getL2Provider().buildCache(REGION, null);
            String principal = "persisted principal";
            int deadline = Math.toIntExact(Instant.now().getEpochSecond() + 15);
            source.put(principal, new AtomicInteger(3), deadline);
            source.put("persistent", new AtomicInteger(0), 0L);
            InetSocketAddress address = AddrUtil.getOneAddress(endpoint);
            J2CacheMemcachedCredentialRetryStore store = store(channel, config, firstClient);
            assertTrue(store.migrateLegacyWindow(principal, address));
            assertFalse(store.migrateLegacyWindow(principal, address));
            assertFalse(store.migrateLegacyWindow("missing", address));
            Attempt next = store.reserve(principal);
            assertEquals(4, next.getCount());
            String target = "shiro-retry:" + digest(namespace) + ":" + digest(principal);
            assertEquals(deadline + ":4:" + next.getWindowId(), firstClient.get(target, new StringTranscoder()));
            assertEquals(3, ((AtomicInteger) source.get(principal)).get());
            assertTrue(store.migrateLegacyWindow("persistent", address));
            Attempt persistent = store.reserve("persistent");
            assertEquals(1, persistent.getCount());
            String persistentKey = "shiro-retry:" + digest(namespace) + ":" + digest("persistent");
            assertEquals("0:1:" + persistent.getWindowId(), firstClient.get(persistentKey, new StringTranscoder()));
        }
    }

    @Test
    public void keepsTheOriginalRegionDeadlineAfterAnUpdate() throws Exception {
        J2CacheConfig config = config(true, "4s");
        try (CacheChannel first = J2CacheTestChannel.open(config); CacheChannel second = J2CacheTestChannel.open(config)) {
            var a = store(first, config, firstClient);
            var b = store(second, config, secondClient);

            Attempt initial = a.reserve("principal");
            long start = System.nanoTime();
            awaitElapsed(start, 2000);
            assertEquals(2, b.reserve("principal").getCount());
            awaitElapsed(start, 4500);
            Attempt renewed = a.reserve("principal");
            assertEquals(1, renewed.getCount());
            assertNotEquals(initial.getWindowId(), renewed.getWindowId());
        }
    }

    @Test
    public void startsANewWindowIfMemcachedStillReturnsAnExpiredWindow() throws Exception {
        J2CacheConfig config = config(true, "4s");
        try (CacheChannel channel = J2CacheTestChannel.open(config)) {
            var store = store(channel, config, firstClient);
            String key = "shiro-retry:" + digest(namespace) + ":" + digest("principal");
            long expiredAt = Instant.now().getEpochSecond() - 1;
            assertTrue(firstClient.set(key, 20, expiredAt + ":8:old-window", new StringTranscoder()));

            Attempt initial = store.reserve("principal");
            assertEquals(1, initial.getCount());
            assertNotEquals("old-window", initial.getWindowId());
            store.clearIfCurrent("principal", "old-window");
            assertEquals(2, store.reserve("principal").getCount());
        }
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void rejectsConfigurationThatCannotStoreSharedCounters() {
        J2CacheConfig config = config(false, "4s");
        try (CacheChannel channel = J2CacheTestChannel.open(config)) {
            assertThrows(IllegalArgumentException.class, () -> store(channel, config, firstClient));
        }
    }

    private J2CacheMemcachedCredentialRetryStore store(CacheChannel channel, J2CacheConfig config,
                                                     MemcachedClient client) {
        return new J2CacheMemcachedCredentialRetryStore(channel, config, REGION, namespace, Object::toString, client);
    }

    private J2CacheConfig config(boolean syncTtl, String ttl) {
        J2CacheConfig config = new J2CacheConfig();
        config.setL1CacheName("caffeine");
        config.setL2CacheName("memcached");
        config.setSyncTtlToRedis(syncTtl);
        config.getL1CacheProperties().setProperty("region." + REGION, "100, " + ttl);
        config.getL2CacheProperties().setProperty("servers", endpoint);
        config.getL2CacheProperties().setProperty("connectionPoolSize", "1");
        config.getL2CacheProperties().setProperty("opTimeout", "3000");
        return config;
    }

    private MemcachedClient client() throws Exception {
        var builder = new XMemcachedClientBuilder(AddrUtil.getAddresses(endpoint));
        builder.setCommandFactory(new BinaryCommandFactory());
        return builder.build();
    }

    private static void awaitElapsed(long start, long millis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(millis) - (System.nanoTime() - start);
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }
}
