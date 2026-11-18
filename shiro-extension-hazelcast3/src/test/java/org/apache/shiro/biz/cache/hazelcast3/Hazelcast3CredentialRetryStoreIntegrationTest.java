package org.apache.shiro.biz.cache.hazelcast3;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.EntryView;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Hazelcast3CredentialRetryStoreIntegrationTest {

    private static HazelcastInstance first;
    private static HazelcastInstance second;
    private static HazelcastInstance client;

    @BeforeClass
    public static void startCluster() throws IOException {
        String cluster = "shiro-hazelcast3-" + UUID.randomUUID();
        int firstPort;
        int secondPort;
        try (ServerSocket a = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket b = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            firstPort = a.getLocalPort();
            secondPort = b.getLocalPort();
        }

        first = Hazelcast.newHazelcastInstance(member(cluster, firstPort, secondPort));
        second = Hazelcast.newHazelcastInstance(member(cluster, secondPort, firstPort));
        ClientConfig proxy = new ClientConfig();
        proxy.getGroupConfig().setName(cluster);
        proxy.setProperty("hazelcast.logging.type", "none");
        proxy.getNetworkConfig().addAddress("127.0.0.1:" + secondPort);
        client = HazelcastClient.newHazelcastClient(proxy);
        assertEquals(2, client.getCluster().getMembers().size());
    }

    @AfterClass
    public static void stopCluster() {
        if (Objects.nonNull(client)) {
            client.shutdown();
        }
        if (Objects.nonNull(second)) {
            second.getLifecycleService().terminate();
        }
        if (Objects.nonNull(first)) {
            first.getLifecycleService().terminate();
        }
    }

    @Test
    public void reservesUniqueCountsFromAMemberAndAThinClient() throws Exception {
        Hazelcast3CredentialRetryStore<String> memberStore = store(first, "atomic");
        Hazelcast3CredentialRetryStore<String> clientStore = store(client, "atomic");
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Attempt>> requests = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                var selected = i % 2 == 0 ? memberStore : clientStore;
                requests.add(() -> selected.reserve("principal"));
            }
            Set<Integer> counts = new HashSet<>();
            Set<String> windows = new HashSet<>();
            for (Future<Attempt> future : workers.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                Attempt attempt = future.get();
                assertTrue(counts.add(attempt.getCount()));
                windows.add(attempt.getWindowId());
            }
            assertEquals(1, windows.size());
            assertEquals(48, counts.size());
            assertTrue(counts.contains(1));
            assertTrue(counts.contains(48));
            assertEquals(49, clientStore.reserve("principal").getCount());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void preservesTheCreationTtlAndDiscardsStaleClears() throws Exception {
        IMap<String, Object> map = first.getMap("ttl");
        var memberStore = store(first, "ttl");
        var clientStore = store(client, "ttl");

        Attempt initial = memberStore.reserve("principal");
        long original = map.getEntryView("principal").getExpirationTime();
        await(original - 1600);
        assertEquals(2, clientStore.reserve("principal").getCount());
        assertTrue(Math.abs(original - map.getEntryView("principal").getExpirationTime()) < 300);
        await(original + 100);
        Attempt renewed = clientStore.reserve("principal");
        assertEquals(1, renewed.getCount());
        assertNotEquals(initial.getWindowId(), renewed.getWindowId());
        memberStore.clearIfCurrent("principal", initial.getWindowId());
        assertEquals(2, clientStore.reserve("principal").getCount());
        memberStore.clearIfCurrent("principal", renewed.getWindowId());
        assertNull(map.getEntryView("principal"));
    }

    @Test
    public void refreshesMaxIdleOnlyForReservations() throws Exception {
        IMap<String, Object> map = first.getMap("idle");
        var memberStore = store(first, "idle");
        var clientStore = store(client, "idle");
        Attempt initial = memberStore.reserve("principal");
        long firstDeadline = map.getEntryView("principal").getExpirationTime();
        await(firstDeadline - 1000);
        assertEquals(2, clientStore.reserve("principal").getCount());
        EntryView<String, Object> after = map.getEntryView("principal");
        assertTrue(after.getExpirationTime() > firstDeadline);
        clientStore.clearIfCurrent("principal", "old-window");
        assertEquals(after.getExpirationTime(), map.getEntryView("principal").getExpirationTime());

        await(after.getExpirationTime() + 100);
        Attempt next = memberStore.reserve("principal");
        assertEquals(1, next.getCount());
        assertNotEquals(initial.getWindowId(), next.getWindowId());
    }

    @Test
    public void preservesIdleExpiryForAThinClientWithStatisticsDisabled() throws Exception {
        var idle = store(client, "idle-no-stats");
        Attempt first = idle.reserve("principal");
        long start = System.currentTimeMillis();
        await(start + 400);
        assertEquals(2, idle.reserve("principal").getCount());
        await(start + 2600);

        Attempt renewed = idle.reserve("principal");
        assertEquals(1, renewed.getCount());
        assertNotEquals(first.getWindowId(), renewed.getWindowId());
    }

    @Test
    public void migratesLegacyCounterAndKeepsItsProviderDeadline() throws Exception {
        String principal = "legacy";
        IMap<String, Object> map = first.getMap("ttl");
        map.set(principal, new AtomicInteger(2), 6, TimeUnit.SECONDS);
        long created = map.getEntryView(principal).getCreationTime();
        await(created + 1100L);
        map.set(principal, new AtomicInteger(3), 6, TimeUnit.SECONDS);
        assertTrue("fixture must have a later update timestamp", map.getEntryView(principal).getLastUpdateTime() > created);
        long originalDeadline = map.getEntryView(principal).getExpirationTime();
        Hazelcast3CredentialRetryStore<String> remoteStore = store(client, "ttl");
        Hazelcast3CredentialRetryStore<String> memberStore = store(first, "ttl");

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

    private static Hazelcast3CredentialRetryStore<String> store(HazelcastInstance instance, String mapName) {
        int maxIdle = first.getConfig().findMapConfig(mapName).getMaxIdleSeconds();
        return new Hazelcast3CredentialRetryStore<>(instance, mapName, maxIdle, Object::toString);
    }

    private static void await(long deadline) throws InterruptedException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private static Config member(String cluster, int ownPort, int otherPort) {
        Config config = new Config();
        config.getGroupConfig().setName(cluster);
        config.setProperty("hazelcast.logging.type", "none");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.thread.count", "2");
        config.setProperty("hazelcast.event.thread.count", "2");
        config.setProperty("hazelcast.wait.seconds.before.join", "0");
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.addMapConfig(new MapConfig("ttl").setTimeToLiveSeconds(4).setMaxIdleSeconds(10));
        config.addMapConfig(new MapConfig("idle").setMaxIdleSeconds(2));
        config.addMapConfig(new MapConfig("idle-no-stats").setMaxIdleSeconds(2).setStatisticsEnabled(false));
        NetworkConfig network = config.getNetworkConfig();
        network.setPort(ownPort).setPortAutoIncrement(false);
        network.getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        network.getJoin().getMulticastConfig().setEnabled(false);
        network.getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1:" + otherPort);
        return config;
    }
}
