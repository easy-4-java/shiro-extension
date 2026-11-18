package org.apache.shiro.biz.cache.spring.redis4;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class SpringDataRedis4CredentialRetryStoresIntegrationTest {

    private static final String CLUSTER_NODES = "shiro.redis.test.cluster.nodes";
    private static final String REGION = "credential-retry";
    private static final StringRedisSerializer UTF_8 = StringRedisSerializer.UTF_8;
    private static LettuceConnectionFactory first;
    private static LettuceConnectionFactory second;

    private final String namespace = "{retry-current}:test:spring-redis4-retry:" + UUID.randomUUID();

    @BeforeClass
    public static void connect() {
        String port = System.getProperty("shiro.redis.test.port");
        String nodes = System.getProperty(CLUSTER_NODES);
        assumeTrue("Supply an isolated Redis port or cluster nodes", Objects.nonNull(port) || Objects.nonNull(nodes));
        first = migrationConnectionFactory(port, nodes);
        second = migrationConnectionFactory(port, nodes);
    }

    @AfterClass
    public static void disconnect() {
        if (Objects.nonNull(second)) {
            second.destroy();
        }
        if (Objects.nonNull(first)) {
            first.destroy();
        }
    }

    @Test
    public void copiesLegacyWindowBeforeIdlePolicyRefreshesIt() {
        String legacyPrefix = "{retry-legacy}:test:" + UUID.randomUUID() + ":";
        verifyDifferentOwners(first, legacyPrefix + "principal", namespace + ":principal");
        var config = RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(name -> legacyPrefix)
                .entryTtl((key, value) -> Objects.isNull(value) ? Duration.ofSeconds(30) : Duration.ofSeconds(5))
                .enableTimeToIdle();
        var manager = RedisCacheManager.builder(first).cacheDefaults(config).transactionAware().build();
        manager.afterPropertiesSet();
        var source = manager.getCache(REGION);
        assertNull(source.putIfAbsent("principal", new AtomicInteger(3)));
        long originalDeadline = expiryKey(legacyPrefix + "principal");
        assertTrue(originalDeadline > System.currentTimeMillis());

        assertTrue(SpringDataRedis4CredentialRetryStores.migrateLegacyWindow(
                source, first, namespace, Object::toString, "principal"));
        assertEquals(originalDeadline, expiryKey(legacyPrefix + "principal"));
        assertEquals(originalDeadline, expiry("principal"));
        assertFalse(SpringDataRedis4CredentialRetryStores.migrateLegacyWindow(
                source, second, namespace, Object::toString, "principal"));
        Attempt next = store(second, config).reserve("principal");
        assertEquals(4, next.getCount());
        assertTrue(expiry("principal") > originalDeadline);
    }

    @Test
    public void evaluatesPerKeyTtlOnceWhenAWindowIsCreated() {
        AtomicInteger creationCalls = new AtomicInteger();
        TtlFunction ttl = (key, value) -> {
            assertEquals(0, ((AtomicInteger) value).get());
            creationCalls.incrementAndGet();
            return "brief".equals(key) ? Duration.ofSeconds(6) : Duration.ofSeconds(12);
        };
        var config = RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl);
        CredentialRetryStore a = store(first, config);
        CredentialRetryStore b = store(second, config);
        Attempt initial = a.reserve("brief");
        long brief = expiry("brief");
        Attempt other = b.reserve("longer");
        long longer = expiry("longer");
        assertEquals(1, other.getCount());
        assertTrue(longer - brief >= 5500);
        assertEquals(initial.getWindowId(), b.reserve("brief").getWindowId());
        assertEquals(brief, expiry("brief"));
        assertEquals(2, creationCalls.get());
    }

    @Test
    public void refreshesTimeToIdleWithReadPolicyAndPreservesTheNewWindowOnStaleClear() throws Exception {
        TtlFunction ttl = (key, value) -> Objects.isNull(value) ? Duration.ofSeconds(9) : Duration.ofSeconds(4);
        var config = RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl).enableTimeToIdle();
        CredentialRetryStore a = store(first, config);
        CredentialRetryStore b = store(second, config);
        Attempt initial = a.reserve("principal");
        long starting = expiry("principal");
        assertTrue(starting > System.currentTimeMillis());
        assertTrue(starting - System.currentTimeMillis() <= 4000);
        Thread.sleep(50);
        assertEquals(2, b.reserve("principal").getCount());
        long renewed = expiry("principal");
        assertTrue(renewed - starting >= 4900);

        b.clearIfCurrent("principal", "old-window");
        assertEquals(renewed, expiry("principal"));
        a.clearIfCurrent("principal", initial.getWindowId());
        Attempt next = b.reserve("principal");
        assertEquals(1, next.getCount());
        assertNotEquals(initial.getWindowId(), next.getWindowId());
        a.clearIfCurrent("principal", initial.getWindowId());
        assertEquals(2, b.reserve("principal").getCount());
    }

    @Test
    public void keepsTtlWhenReadPolicyReturnsZeroAndTreatsSubmillisecondWritesAsPersistent() {
        TtlFunction ttl = (key, value) -> Objects.isNull(value) ? Duration.ZERO
                : ("persistent".equals(key) ? Duration.ofNanos(1) : Duration.ofSeconds(8));
        var config = RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl).enableTimeToIdle();
        CredentialRetryStore store = store(first, config);

        store.reserve("ordinary");
        long deadline = expiry("ordinary");
        assertTrue(deadline > 0);
        assertEquals(2, store.reserve("ordinary").getCount());
        assertEquals(deadline, expiry("ordinary"));

        store.reserve("persistent");
        assertEquals(-1L, expiry("persistent"));
    }

    @Test
    public void concurrentCreationAttemptsShareOneWindow() throws Exception {
        CountDownLatch bothCreating = new CountDownLatch(2);
        TtlFunction ttl = (key, value) -> {
            bothCreating.countDown();
            try {
                assertTrue(bothCreating.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return Duration.ofSeconds(10);
        };
        var config = RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl);
        CredentialRetryStore a = store(first, config);
        CredentialRetryStore b = store(second, config);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var left = workers.submit(() -> a.reserve("principal"));
            var right = workers.submit(() -> b.reserve("principal"));
            Attempt firstAttempt = left.get(8, TimeUnit.SECONDS);
            Attempt secondAttempt = right.get(8, TimeUnit.SECONDS);
            assertNotEquals(firstAttempt.getCount(), secondAttempt.getCount());
            assertEquals(3, firstAttempt.getCount() + secondAttempt.getCount());
            assertEquals(firstAttempt.getWindowId(), secondAttempt.getWindowId());
            assertEquals(3, b.reserve("principal").getCount());
        } finally {
            workers.shutdownNow();
        }
    }

    private CredentialRetryStore store(LettuceConnectionFactory factory, RedisCacheConfiguration config) {
        RedisCacheManager manager = RedisCacheManager.builder(factory)
                .withCacheConfiguration(REGION, config).transactionAware().build();
        manager.afterPropertiesSet();
        return SpringDataRedis4CredentialRetryStores.fromCache(
                manager.getCache(REGION), factory, namespace, Object::toString);
    }

    private long expiry(String principal) {
        return expiryKey(namespace + ":" + principal);
    }

    private long expiryKey(String key) {
        try (RedisConnection connection = first.getConnection()) {
            return connection.scriptingCommands().eval(
                    UTF_8.serialize("return redis.call('PEXPIRETIME', KEYS[1])"),
                    ReturnType.INTEGER, 1, UTF_8.serialize(key));
        }
    }

    private static LettuceConnectionFactory migrationConnectionFactory(String port, String nodes) {
        if (Objects.isNull(nodes)) {
            return connectionFactory(port);
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisClusterConfiguration(Arrays.asList(nodes.split(","))));
        factory.afterPropertiesSet();
        return factory;
    }

    private static void verifyDifferentOwners(LettuceConnectionFactory factory, String source, String destination) {
        if (Objects.nonNull(System.getProperty(CLUSTER_NODES))) {
            try (RedisClusterConnection cluster = factory.getClusterConnection()) {
                String sourceOwner = cluster.clusterGetNodeForKey(UTF_8.serialize(source)).getId();
                String destinationOwner = cluster.clusterGetNodeForKey(UTF_8.serialize(destination)).getId();
                assertNotEquals("Migration must cross Redis primary nodes", sourceOwner, destinationOwner);
            }
        }
    }

    private static LettuceConnectionFactory connectionFactory(String port) {
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        factory.afterPropertiesSet();
        return factory;
    }
}
