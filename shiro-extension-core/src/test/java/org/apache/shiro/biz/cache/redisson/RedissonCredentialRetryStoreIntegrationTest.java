package org.apache.shiro.biz.cache.redisson;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.RedissonObject;
import org.redisson.api.RMapCache;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;
import redis.clients.util.JedisClusterCRC16;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class RedissonCredentialRetryStoreIntegrationTest {

    private static final String CLUSTER_NODES = "shiro.redis.test.cluster.nodes";
    private static RedissonClient firstClient;
    private static RedissonClient secondClient;

    private final String namespace = "{retry-current}:test:redisson-retry:" + UUID.randomUUID();
    private final String legacyNamespace = "{retry-legacy}:test:redisson-retry:" + UUID.randomUUID();

    @BeforeClass
    public static void connectTwoClients() {
        String port = System.getProperty("shiro.redis.test.port");
        assumeTrue("Supply an isolated Redis port or cluster", Objects.nonNull(port)
                || Objects.nonNull(System.getProperty(CLUSTER_NODES)));
        firstClient = client(port);
        secondClient = client(port);
    }

    @AfterClass
    public static void disconnectClients() {
        if (Objects.nonNull(secondClient)) {
            secondClient.shutdown();
        }
        if (Objects.nonNull(firstClient)) {
            firstClient.shutdown();
        }
    }

    @Test
    public void independentClientsReserveUniqueCountsAndPreserveTtl() throws Exception {
        RedissonCredentialRetryStore first = store(firstClient, Duration.ofSeconds(15), Duration.ZERO);
        RedissonCredentialRetryStore second = store(secondClient, Duration.ofSeconds(15), Duration.ZERO);
        Attempt initial = first.reserve("principal");
        long deadline = deadline();
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Attempt>> requests = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                RedissonCredentialRetryStore selected = i % 2 == 0 ? first : second;
                requests.add(() -> selected.reserve("principal"));
            }
            Set<Integer> counts = new HashSet<>();
            for (Future<Attempt> result : workers.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                Attempt attempt = result.get();
                assertEquals(initial.getWindowId(), attempt.getWindowId());
                assertTrue(counts.add(attempt.getCount()));
            }
            for (int count = 2; count <= 33; count++) {
                assertTrue(counts.contains(count));
            }
            assertEquals(deadline, deadline());
            first.clearIfCurrent("principal", initial.getWindowId());
            assertEquals(-2L, deadline());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void migratesTheLegacyMapEntryWithoutRefreshingItsIdleDeadline() throws Exception {
        String mapName = legacyNamespace + ":legacy";
        assertDifferentPrimaries(mapName, namespace + ":principal");
        RMapCache<String, AtomicInteger> source = firstClient.getMapCache(mapName,
                new CompositeCodec(StringCodec.INSTANCE, new SerializationCodec(), new SerializationCodec()));
        long beforeWrite = System.currentTimeMillis();
        source.put("principal", new AtomicInteger(3), 7000, TimeUnit.MILLISECONDS, 6000, TimeUnit.MILLISECONDS);
        long afterWrite = System.currentTimeMillis();
        long ttl = sourceDeadline(mapName, "redisson__timeout__set");
        long idle = sourceDeadline(mapName, "redisson__idle__set");
        // Redisson samples the clock separately for the two expiration policies.
        assertTrue(ttl >= beforeWrite + 7000 && ttl <= afterWrite + 7000);
        assertTrue(idle >= beforeWrite + 6000 && idle <= afterWrite + 6000);
        assertTrue(idle < ttl);
        RedissonCredentialRetryStore store = store(secondClient, Duration.ofSeconds(30), Duration.ofSeconds(30));
        assertTrue(store.migrateLegacyWindow(source, "principal"));
        assertFalse(store.migrateLegacyWindow(source, "principal"));
        assertFalse(store.migrateLegacyWindow(source, "missing"));
        assertEquals(idle, deadline());
        assertEquals(idle, sourceDeadline(mapName, "redisson__idle__set"));
        assertEquals(ttl, sourceDeadline(mapName, "redisson__timeout__set"));

        awaitDeadline(idle - 4900);
        assertEquals(4, store.reserve("principal").getCount());
        assertEquals(ttl, deadline());
    }

    private long sourceDeadline(String name, String prefix) {
        return firstClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "return tonumber(redis.call('ZSCORE', KEYS[1], ARGV[1]))",
                RScript.ReturnType.INTEGER, Collections.<Object>singletonList(RedissonObject.prefixName(prefix, name)), "principal");
    }

    @Test
    public void idleRefreshIsCappedByTtlAndStaleClearDoesNotRefreshIt() throws Exception {
        RedissonCredentialRetryStore first = store(firstClient, Duration.ofMillis(3000), Duration.ofMillis(2300));
        RedissonCredentialRetryStore second = store(secondClient, Duration.ofMillis(3000), Duration.ofMillis(2300));
        Attempt initial = first.reserve("principal");
        long idleDeadline = deadline();
        awaitDeadline(idleDeadline - 1250);

        assertEquals(2, second.reserve("principal").getCount());
        long cappedDeadline = deadline();
        assertEquals(700, cappedDeadline - idleDeadline);
        first.clearIfCurrent("principal", "stale");
        assertEquals(cappedDeadline, deadline());

        awaitDeadline(cappedDeadline + 50);
        Attempt next = second.reserve("principal");
        assertEquals(1, next.getCount());
        assertNotEquals(initial.getWindowId(), next.getWindowId());
        first.clearIfCurrent("principal", initial.getWindowId());
        assertEquals(2, second.reserve("principal").getCount());
    }

    @Test
    public void keepsMapExpiryAndImportsPersistentZeroCounts() {
        RedissonCredentialRetryStore store = store(secondClient, Duration.ofSeconds(30), Duration.ofSeconds(30));
        RMapCache<String, AtomicInteger> persistent = firstClient.getMapCache(legacyNamespace + ":persistent", new SerializationCodec());
        persistent.put("principal", new AtomicInteger(0));
        assertTrue(store.migrateLegacyWindow(persistent, "principal"));
        Attempt attempt = store.reserve("principal");
        assertEquals(1, attempt.getCount());
        assertEquals(-1L, deadline());
        store.clearIfCurrent("principal", attempt.getWindowId());

        RMapCache<String, AtomicInteger> bounded = firstClient.getMapCache(legacyNamespace + ":bounded", new SerializationCodec());
        bounded.put("principal", new AtomicInteger(3), 10, TimeUnit.SECONDS);
        assertTrue(bounded.expire(5, TimeUnit.SECONDS));
        long mapDeadline = deadline(bounded.getName());
        assertTrue(mapDeadline > System.currentTimeMillis());
        assertTrue(store.migrateLegacyWindow(bounded, "principal"));
        assertEquals(4, store.reserve("principal").getCount());
        assertEquals(mapDeadline, deadline());
    }

    @Test
    public void idleOnlyWindowExpiresAndZeroPoliciesStayPersistent() throws Exception {
        RedissonCredentialRetryStore idleOnly = store(firstClient, Duration.ZERO, Duration.ofMillis(1200));
        Attempt initial = idleOnly.reserve("principal");
        long deadline = deadline();
        awaitDeadline(deadline - 600);
        assertEquals(2, idleOnly.reserve("principal").getCount());
        assertTrue(deadline() > deadline);
        awaitDeadline(deadline() + 50);
        assertEquals(-2L, deadline());

        RedissonCredentialRetryStore persistent = store(secondClient, Duration.ZERO, Duration.ZERO);
        Attempt next = persistent.reserve("principal");
        assertEquals(1, next.getCount());
        assertNotEquals(initial.getWindowId(), next.getWindowId());
        assertEquals(-1L, deadline());
    }

    private RedissonCredentialRetryStore store(RedissonClient client, Duration ttl, Duration idle) {
        return new RedissonCredentialRetryStore(client, namespace, Object::toString, ttl, idle);
    }

    private long deadline() {
        return deadline(namespace + ":principal");
    }

    private long deadline(String key) {
        return firstClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "return redis.call('PEXPIRETIME', KEYS[1])",
                RScript.ReturnType.INTEGER, Collections.<Object>singletonList(key));
    }

    private static void awaitDeadline(long deadline) throws InterruptedException {
        long millis = deadline - System.currentTimeMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static RedissonClient client(String port) {
        Config config = new Config().setCodec(StringCodec.INSTANCE).setThreads(2).setNettyThreads(2);
        String cluster = System.getProperty(CLUSTER_NODES);
        if (Objects.nonNull(cluster)) {
            config.useClusterServers().addNodeAddress(Arrays.stream(cluster.split(","))
                            .map(node -> "redis://" + node).toArray(String[]::new))
                    .setMasterConnectionMinimumIdleSize(1).setMasterConnectionPoolSize(4)
                    .setSlaveConnectionMinimumIdleSize(1).setSlaveConnectionPoolSize(4)
                    .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        } else {
            config.useSingleServer().setAddress("redis://127.0.0.1:" + port)
                    .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                    .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        }
        return Redisson.create(config);
    }

    private static void assertDifferentPrimaries(String source, String destination) {
        String cluster = System.getProperty(CLUSTER_NODES);
        if (Objects.nonNull(cluster)) {
            RedisClusterClient topology = RedisClusterClient.create(Arrays.stream(cluster.split(","))
                    .map(node -> RedisURI.create("redis://" + node)).collect(Collectors.toList()));
            try {
                String sourceOwner = topology.getPartitions().getPartitionBySlot(JedisClusterCRC16.getSlot(source)).getNodeId();
                String destinationOwner = topology.getPartitions().getPartitionBySlot(JedisClusterCRC16.getSlot(destination)).getNodeId();
                assertNotEquals("Migration must cross Redis primary nodes", sourceOwner, destinationOwner);
            } finally {
                topology.shutdown();
            }
        }
    }
}
