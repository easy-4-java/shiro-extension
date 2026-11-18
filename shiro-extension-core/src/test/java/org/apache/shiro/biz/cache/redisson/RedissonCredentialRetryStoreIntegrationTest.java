package org.apache.shiro.biz.cache.redisson;

import java.time.Duration;
import java.util.ArrayList;
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
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class RedissonCredentialRetryStoreIntegrationTest {

    private static RedissonClient firstClient;
    private static RedissonClient secondClient;

    private final String namespace = "test:redisson-retry:" + UUID.randomUUID();

    @BeforeClass
    public static void connectTwoClients() {
        String port = System.getProperty("shiro.redis.test.port");
        assumeTrue("Supply an isolated Redis port", Objects.nonNull(port));
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
        return firstClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "return redis.call('PEXPIRETIME', KEYS[1])",
                RScript.ReturnType.INTEGER, List.of(namespace + ":principal"));
    }

    private static void awaitDeadline(long deadline) throws InterruptedException {
        long millis = deadline - System.currentTimeMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static RedissonClient client(String port) {
        Config config = new Config().setCodec(StringCodec.INSTANCE).setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }
}
