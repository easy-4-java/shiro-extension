package org.apache.shiro.biz.cache.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.EntryView;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HazelcastCredentialRetryStoreIntegrationTest {

    private static HazelcastInstance first;
    private static HazelcastInstance second;

    @BeforeClass
    public static void startCluster() throws IOException {
        String cluster = "shiro-map-" + UUID.randomUUID();
        int firstPort;
        int secondPort;
        try (ServerSocket a = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket b = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            firstPort = a.getLocalPort();
            secondPort = b.getLocalPort();
        }
        first = Hazelcast.newHazelcastInstance(member(cluster, firstPort, secondPort));
        second = Hazelcast.newHazelcastInstance(member(cluster, secondPort, firstPort));
        assertEquals(2, first.getCluster().getMembers().size());
    }

    @AfterClass
    public static void stopCluster() {
        if (second != null) {
            second.getLifecycleService().terminate();
        }
        if (first != null) {
            first.getLifecycleService().terminate();
        }
    }

    @Test
    public void reservesUniqueCountsAcrossMembersAndSaturates() throws Exception {
        IMap<String, Attempt> map = first.getMap("atomic");
        HazelcastCredentialRetryStore<String> firstStore = new HazelcastCredentialRetryStore<>(map, Object::toString);
        HazelcastCredentialRetryStore<String> secondStore = store(second, "atomic");
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Attempt>> attempts = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                HazelcastCredentialRetryStore<String> selected = i % 2 == 0 ? firstStore : secondStore;
                attempts.add(() -> selected.reserve("principal"));
            }
            Set<String> windows = new HashSet<>();
            Set<Integer> counts = new HashSet<>();
            for (Future<Attempt> result : workers.invokeAll(attempts, 10, TimeUnit.SECONDS)) {
                Attempt attempt = result.get();
                windows.add(attempt.getWindowId());
                assertTrue(counts.add(attempt.getCount()));
            }
            assertEquals(1, windows.size());
            assertEquals(48, counts.size());
            assertEquals(48, map.get("principal").getCount());
            map.put("principal", new Attempt(windows.iterator().next(), Integer.MAX_VALUE));
            assertEquals(Integer.MAX_VALUE, secondStore.reserve("principal").getCount());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void incrementsRetainOriginalTtlAndStaleClearPreservesNewWindow() throws Exception {
        IMap<String, Attempt> map = first.getMap("ttl");
        HazelcastCredentialRetryStore<String> firstStore = store(first, "ttl");
        HazelcastCredentialRetryStore<String> secondStore = store(second, "ttl");

        Attempt initial = firstStore.reserve("principal");
        long originalDeadline = map.getEntryView("principal").getExpirationTime();
        awaitDeadline(originalDeadline - 1500);
        assertEquals(2, secondStore.reserve("principal").getCount());
        assertEquals(originalDeadline, map.getEntryView("principal").getExpirationTime());
        awaitDeadline(originalDeadline + 100);

        Attempt renewed = secondStore.reserve("principal");
        assertEquals(1, renewed.getCount());
        assertNotEquals(initial.getWindowId(), renewed.getWindowId());
        firstStore.clearIfCurrent("principal", initial.getWindowId());
        assertEquals(2, secondStore.reserve("principal").getCount());
        firstStore.clearIfCurrent("principal", renewed.getWindowId());
        assertNull(map.getEntryView("principal"));
    }

    @Test
    public void reservationRefreshesIdleTimeAndStaleClearKeepsTheIdleDeadline() throws Exception {
        IMap<String, Attempt> map = first.getMap("idle");
        HazelcastCredentialRetryStore<String> firstStore = store(first, "idle");
        HazelcastCredentialRetryStore<String> secondStore = store(second, "idle");

        Attempt initial = firstStore.reserve("principal");
        long creationDeadline = map.getEntryView("principal").getExpirationTime();
        awaitDeadline(creationDeadline - 1000);
        assertEquals(2, secondStore.reserve("principal").getCount());
        EntryView<String, Attempt> accessed = map.getEntryView("principal");
        assertTrue(accessed.getExpirationTime() > creationDeadline);
        firstStore.clearIfCurrent("principal", "stale");
        assertEquals(accessed.getExpirationTime(), map.getEntryView("principal").getExpirationTime());

        awaitDeadline(accessed.getExpirationTime() + 100);
        Attempt next = secondStore.reserve("principal");
        assertEquals(1, next.getCount());
        assertNotEquals(initial.getWindowId(), next.getWindowId());
    }

    @Test
    public void migratesLegacyCounterAndKeepsItsProviderDeadline() throws Exception {
        String principal = "legacy";
        IMap<String, Object> map = first.getMap("ttl");
        map.set(principal, new AtomicInteger(2), 6, TimeUnit.SECONDS);
        map.set(principal, new AtomicInteger(3), 6, TimeUnit.SECONDS);
        long originalDeadline = map.getEntryView(principal).getExpirationTime();
        HazelcastCredentialRetryStore<String> remoteStore = store(second, "ttl");
        HazelcastCredentialRetryStore<String> memberStore = store(first, "ttl");

        Attempt adopted = remoteStore.reserve(principal);
        assertEquals(4, adopted.getCount());
        assertEquals(originalDeadline, map.getEntryView(principal).getExpirationTime());
        memberStore.clearIfCurrent(principal, "stale");
        Attempt next = memberStore.reserve(principal);
        assertEquals(5, next.getCount());
        assertEquals(adopted.getWindowId(), next.getWindowId());
        assertEquals(originalDeadline, map.getEntryView(principal).getExpirationTime());

        String unconverted = "unconverted";
        map.set(unconverted, new AtomicInteger(2), 6, TimeUnit.SECONDS);
        long unconvertedDeadline = map.getEntryView(unconverted).getExpirationTime();
        remoteStore.clearIfCurrent(unconverted, "stale");
        assertEquals(unconvertedDeadline, map.getEntryView(unconverted).getExpirationTime());
        assertEquals(2, ((AtomicInteger) map.getEntryView(unconverted).getValue()).get());
    }

    private static HazelcastCredentialRetryStore<String> store(HazelcastInstance member, String name) {
        return new HazelcastCredentialRetryStore<>(member.getMap(name), Object::toString);
    }

    private static void awaitDeadline(long deadline) throws InterruptedException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private static Config member(String cluster, int ownPort, int otherPort) {
        Config config = new Config().setClusterName(cluster);
        config.setProperty("hazelcast.logging.type", "none");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.thread.count", "2");
        config.setProperty("hazelcast.event.thread.count", "2");
        config.setProperty("hazelcast.wait.seconds.before.join", "0");
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.addMapConfig(new MapConfig("ttl").setTimeToLiveSeconds(3).setMaxIdleSeconds(10));
        config.addMapConfig(new MapConfig("idle").setMaxIdleSeconds(2));
        NetworkConfig network = config.getNetworkConfig();
        network.setPort(ownPort).setPortAutoIncrement(false);
        network.getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        network.getJoin().getAutoDetectionConfig().setEnabled(false);
        network.getJoin().getMulticastConfig().setEnabled(false);
        network.getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1:" + otherPort);
        return config;
    }
}
