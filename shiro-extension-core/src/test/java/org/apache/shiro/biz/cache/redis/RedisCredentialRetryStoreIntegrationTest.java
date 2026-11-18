package org.apache.shiro.biz.cache.redis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.apache.commons.io.IOUtils;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class RedisCredentialRetryStoreIntegrationTest {

    private List<String> redisCommand;
    private final String namespace = "test:shiro-retry:" + UUID.randomUUID();

    @Before
    public void requireIsolatedRedis() {
        String socket = System.getProperty("shiro.redis.test.socket");
        String container = System.getProperty("shiro.redis.test.container");
        assumeTrue("Supply an isolated Redis socket or container", Objects.nonNull(socket) || Objects.nonNull(container));
        redisCommand = Objects.nonNull(socket)
                ? Arrays.asList("redis-cli", "--raw", "-s", socket)
                : Arrays.asList("docker", "exec", container, "redis-cli", "--raw");
    }

    @Test
    public void independentAdaptersShareAnAtomicCounterWithoutExtendingTtl() throws Exception {
        RedisCredentialRetryStore first = newStore(Duration.ofSeconds(20));
        RedisCredentialRetryStore second = newStore(Duration.ofSeconds(20));
        CredentialRetryStore.Attempt initial = first.reserve("alice");
        assertEquals(1, initial.getCount());
        long before = Long.parseLong(redis("PEXPIRETIME", key("alice")));
        assertTrue(before > 0);

        ExecutorService threads = Executors.newFixedThreadPool(6);
        try {
            List<Callable<CredentialRetryStore.Attempt>> requests = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                RedisCredentialRetryStore selected = index % 2 == 0 ? first : second;
                requests.add(() -> selected.reserve("alice"));
            }

            Set<Integer> counts = new HashSet<>();
            for (Future<CredentialRetryStore.Attempt> result : threads.invokeAll(requests)) {
                CredentialRetryStore.Attempt attempt = result.get();
                assertEquals(initial.getWindowId(), attempt.getWindowId());
                assertTrue(counts.add(attempt.getCount()));
            }
            for (int count = 2; count <= 17; count++) {
                assertTrue(counts.contains(count));
            }
            long after = Long.parseLong(redis("PEXPIRETIME", key("alice")));
            assertEquals("provider expiration deadline must be preserved", before, after);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void staleSuccessCannotClearNewWindowAfterRedisExpiry() throws Exception {
        RedisCredentialRetryStore store = newStore(Duration.ofSeconds(20));
        CredentialRetryStore.Attempt first = store.reserve("alice");
        redis("PEXPIRE", key("alice"), "1");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!"0".equals(redis("EXISTS", key("alice"))) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("0", redis("EXISTS", key("alice")));

        CredentialRetryStore.Attempt second = store.reserve("alice");
        assertEquals(1, second.getCount());
        assertNotEquals(first.getWindowId(), second.getWindowId());
        store.clearIfCurrent("alice", first.getWindowId());
        assertEquals(2, store.reserve("alice").getCount());
        store.clearIfCurrent("alice", second.getWindowId());
        assertEquals(1, store.reserve("alice").getCount());
    }

    @Test
    public void importsPersistentZeroCounterAndRejectsAnAlreadyExpiredWindow() {
        RedisCredentialRetryStore store = newStore(Duration.ofSeconds(20));
        assertFalse(store.importFixedWindow("expired", 3, 1));
        assertEquals("0", redis("EXISTS", key("expired")));
        assertTrue(store.importFixedWindow("persistent", 0, 0));
        assertEquals(1, store.reserve("persistent").getCount());
        assertEquals("-1", redis("PTTL", key("persistent")));
        assertFalse(store.importFixedWindow("persistent", 99, 0));
        assertEquals(2, store.reserve("persistent").getCount());
    }

    @Test
    public void preservesConfiguredPersistenceAndSaturatesCount() {
        RedisCredentialRetryStore store = newStore(Duration.ZERO);
        store.reserve("alice");
        redis("HSET", key("alice"), "count", Integer.toString(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, store.reserve("alice").getCount());
        assertEquals("-1", redis("PTTL", key("alice")));
    }

    private RedisCredentialRetryStore newStore(Duration expiry) {
        return new RedisCredentialRetryStore(this::eval, namespace, Object::toString, expiry);
    }

    private String key(String principal) {
        return namespace + ":" + principal;
    }

    private String eval(String script, List<String> keys, List<String> args) {
        List<String> command = new ArrayList<>(Arrays.asList("EVAL", script, Integer.toString(keys.size())));
        command.addAll(keys);
        command.addAll(args);
        return redis(command.toArray(new String[0]));
    }

    private String redis(String... args) {
        List<String> command = new ArrayList<>(redisCommand);
        command.addAll(Arrays.asList(args));

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Redis fixture command timed out");
            }
            String output = IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.startsWith("ERR ")) {
                throw new IllegalStateException("Redis fixture command failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run Redis fixture", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running Redis fixture", e);
        }
    }
}
