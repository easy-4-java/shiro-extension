package org.apache.shiro.biz.cache.jcache;

import com.hazelcast.cache.impl.HazelcastServerCachingProvider;
import com.hazelcast.config.Config;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.expiry.ModifiedExpiryPolicy;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JCacheCredentialRetryStoreIntegrationTest {

    private static HazelcastInstance firstMember;
    private static HazelcastInstance secondMember;
    private static CacheManager firstManager;
    private static CacheManager secondManager;

    @BeforeClass
    public static void startCluster() throws IOException {
        String cluster = "shiro-retry-" + UUID.randomUUID();
        int firstPort;
        int secondPort;
        try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket second = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            firstPort = first.getLocalPort();
            secondPort = second.getLocalPort();
        }
        firstMember = Hazelcast.newHazelcastInstance(member(cluster, firstPort, secondPort));
        secondMember = Hazelcast.newHazelcastInstance(member(cluster, secondPort, firstPort));
        assertEquals(2, firstMember.getCluster().getMembers().size());
        firstManager = HazelcastServerCachingProvider.createCachingProvider(firstMember).getCacheManager();
        secondManager = HazelcastServerCachingProvider.createCachingProvider(secondMember).getCacheManager();
    }

    @AfterClass
    public static void stopCluster() {
        if (secondMember != null) {
            secondMember.getLifecycleService().terminate();
        }
        if (firstMember != null) {
            firstMember.getLifecycleService().terminate();
        }
    }

    @Test
    public void incrementsAtomicallyAcrossMembersAndSaturates() throws Exception {
        Cache<Object, Attempt> cache = createCache("atomic", EternalExpiryPolicy.factoryOf());
        JCacheCredentialRetryStore first = new JCacheCredentialRetryStore(cache);
        JCacheCredentialRetryStore second = new JCacheCredentialRetryStore(
                secondManager.getCache("atomic", Object.class, Attempt.class));
        ExecutorService threads = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Attempt>> attempts = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                JCacheCredentialRetryStore selected = index % 2 == 0 ? first : second;
                attempts.add(() -> selected.reserve("alice"));
            }

            Set<String> windows = new HashSet<>();
            Set<Integer> counts = new HashSet<>();
            for (Future<Attempt> future : threads.invokeAll(attempts, 10, TimeUnit.SECONDS)) {
                Attempt attempt = future.get();
                windows.add(attempt.getWindowId());
                assertTrue(counts.add(attempt.getCount()));
            }
            assertEquals(1, windows.size());
            assertEquals(20, counts.size());
            assertEquals(20, cache.get("alice").getCount());
            cache.put("alice", new Attempt(cache.get("alice").getWindowId(), Integer.MAX_VALUE));
            assertEquals(Integer.MAX_VALUE, second.reserve("alice").getCount());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void preservesExpiryAcrossUpdatesAndRejectsStaleReset() throws Exception {
        Cache<Object, Attempt> cache = createCache("expiration",
                CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.SECONDS, 3)));
        JCacheCredentialRetryStore first = new JCacheCredentialRetryStore(cache);
        JCacheCredentialRetryStore second = new JCacheCredentialRetryStore(
                secondManager.getCache("expiration", Object.class, Attempt.class));

        Attempt initial = first.reserve("alice");
        long start = System.nanoTime();
        awaitElapsed(start, 1400);
        assertEquals(2, second.reserve("alice").getCount());
        awaitElapsed(start, 3300);
        assertNull(cache.get("alice"));

        Attempt renewed = second.reserve("alice");
        assertEquals(1, renewed.getCount());
        assertNotEquals(initial.getWindowId(), renewed.getWindowId());
        first.clearIfCurrent("alice", initial.getWindowId());
        assertEquals(2, first.reserve("alice").getCount());
        second.clearIfCurrent("alice", renewed.getWindowId());
        assertNull(cache.get("alice"));
    }

    @Test
    public void rejectsCacheWhosePolicyRestartsExpiryOnUpdate() {
        Cache<Object, Attempt> cache = createCache("sliding",
                ModifiedExpiryPolicy.factoryOf(new Duration(TimeUnit.SECONDS, 20)));
        assertThrows(IllegalArgumentException.class, () -> new JCacheCredentialRetryStore(cache));
    }

    private static void awaitElapsed(long start, long millis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(millis) - (System.nanoTime() - start);
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static Cache<Object, Attempt> createCache(String name, Factory<? extends ExpiryPolicy> policy) {
        return firstManager.createCache(name, new MutableConfiguration<Object, Attempt>()
                .setTypes(Object.class, Attempt.class).setExpiryPolicyFactory(policy).setStoreByValue(true));
    }

    private static Config member(String cluster, int ownPort, int otherPort) {
        Config configuration = new Config();
        configuration.getGroupConfig().setName(cluster);
        configuration.setProperty("hazelcast.logging.type", "none");
        configuration.setProperty("hazelcast.operation.thread.count", "2");
        configuration.setProperty("hazelcast.operation.generic.thread.count", "2");
        configuration.setProperty("hazelcast.io.thread.count", "2");
        configuration.setProperty("hazelcast.event.thread.count", "2");
        configuration.setProperty("hazelcast.wait.seconds.before.join", "0");
        configuration.setProperty("hazelcast.socket.bind.any", "false");
        NetworkConfig network = configuration.getNetworkConfig();
        network.setPort(ownPort).setPortAutoIncrement(false);
        network.getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        network.getJoin().getMulticastConfig().setEnabled(false);
        network.getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1:" + otherPort);
        return configuration;
    }
}
