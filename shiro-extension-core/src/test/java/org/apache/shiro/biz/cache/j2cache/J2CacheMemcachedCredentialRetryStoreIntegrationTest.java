package org.apache.shiro.biz.cache.j2cache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
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
import net.rubyeye.xmemcached.transcoders.CachedData;
import net.rubyeye.xmemcached.transcoders.SerializingTranscoder;
import net.rubyeye.xmemcached.transcoders.StringTranscoder;
import net.rubyeye.xmemcached.utils.AddrUtil;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.apache.shiro.cache.CacheException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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
            try (Socket connection = new Socket()) {
                connection.connect(address, 3000);
                assertThrows(IllegalArgumentException.class,
                        () -> store.migrateLegacyWindowFromMetaConnection("persistent", connection));
                connection.setSoTimeout(3000);
                assertTrue(store.migrateLegacyWindowFromMetaConnection("persistent", connection));
                assertFalse(store.migrateLegacyWindowFromMetaConnection("persistent", connection));
            }
            Attempt persistent = store.reserve("persistent");
            assertEquals(1, persistent.getCount());
            String persistentKey = "shiro-retry:" + digest(namespace) + ":" + digest("persistent");
            assertEquals("0:1:" + persistent.getWindowId(), firstClient.get(persistentKey, new StringTranscoder()));
        }
    }

    @Test
    public void importsThroughAnAuthenticatedMetaConnectionWithItsOriginalDeadline() throws Exception {
        String authPort = System.getProperty("shiro.memcached.auth.test.port");
        assumeTrue("Supply an isolated Memcached ASCII authentication port", Objects.nonNull(authPort));
        SerializationUtils.init("java", new Properties());
        J2CacheConfig config = config(true, "30s");
        String principal = "authenticated-cutover";
        int deadline = Math.toIntExact(Instant.now().getEpochSecond() + 15);
        byte[] credentials = "fixture-user fixture-password".getBytes(StandardCharsets.US_ASCII);
        CachedData seed = new SerializingTranscoder().encode(SerializationUtils.serialize(new AtomicInteger(3)));
        byte[] payload = seed.getData();
        try (Socket source = new Socket("127.0.0.1", Integer.parseInt(authPort));
             CacheChannel channel = J2CacheTestChannel.open(config)) {
            source.setSoTimeout(3000);
            BufferedReader reply = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream requests = source.getOutputStream();
            requests.write(("set auth 0 0 " + credentials.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            requests.write(credentials);
            requests.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            requests.flush();
            assertEquals("STORED", reply.readLine());
            String set = "set " + REGION + ":" + principal + " " + seed.getFlag() + " " + deadline
                    + " " + payload.length + "\r\n";
            requests.write(set.getBytes(StandardCharsets.US_ASCII));
            requests.write(payload);
            requests.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            requests.flush();
            assertEquals("STORED", reply.readLine());

            J2CacheMemcachedCredentialRetryStore store = store(channel, config, firstClient);
            assertTrue(store.migrateLegacyWindowFromMetaConnection(principal, source));
            assertFalse(store.migrateLegacyWindowFromMetaConnection(principal, source));
            Attempt next = store.reserve(principal);
            assertEquals(4, next.getCount());
            String target = "shiro-retry:" + digest(namespace) + ":" + digest(principal);
            assertEquals(deadline + ":4:" + next.getWindowId(), firstClient.get(target, new StringTranscoder()));
        }
    }

    @Test
    public void refusesToImportWhenOnlyTheBinaryProtocolCanReadTheCounter() throws Exception {
        String binaryPort = System.getProperty("shiro.memcached.binary.test.port");
        assumeTrue("Supply an isolated binary-only Memcached port", Objects.nonNull(binaryPort));
        String binaryEndpoint = "127.0.0.1:" + binaryPort;
        SerializationUtils.init("java", new Properties());
        J2CacheConfig config = config(true, "30s");
        String principal = "binary-cutover-" + UUID.randomUUID();
        String legacyKey = REGION + ":" + principal;
        String target = "shiro-retry:" + digest(namespace) + ":" + digest(principal);
        SerializingTranscoder transcoder = new SerializingTranscoder();
        int sourceDeadline = Math.toIntExact(Instant.now().getEpochSecond() + 120);
        byte[] counter = SerializationUtils.serialize(new AtomicInteger(3));
        MemcachedClient legacy = client(binaryEndpoint);
        try (CacheChannel channel = J2CacheTestChannel.open(config)) {
            assertTrue(legacy.set(legacyKey, sourceDeadline, counter, transcoder));
            byte[] storedCounter = (byte[]) legacy.get(legacyKey, transcoder);
            assertEquals(3, ((AtomicInteger) SerializationUtils.deserialize(storedCounter)).get());
            long originalCas = legacy.gets(legacyKey, transcoder).getCas();

            J2CacheMemcachedCredentialRetryStore store = store(channel, config, firstClient);
            assertThrows(CacheException.class,
                    () -> store.migrateLegacyWindow(principal, AddrUtil.getOneAddress(binaryEndpoint)));
            assertNull(firstClient.get(target, new StringTranscoder()));
            assertTrue(store.migrateLegacyWindowFromSnapshot(principal, 3, sourceDeadline));
            assertEquals(4, store.reserve(principal).getCount());
            assertEquals(originalCas, legacy.gets(legacyKey, transcoder).getCas());
        } finally {
            legacy.shutdown();
        }
    }

    @Test
    public void importsAuthoritativeSnapshotWithTheOriginalServerDeadline() throws Exception {
        J2CacheConfig config = config(true, "30s");
        try (CacheChannel channel = J2CacheTestChannel.open(config)) {
            J2CacheMemcachedCredentialRetryStore store = store(channel, config, firstClient);
            String principal = "snapshot-" + UUID.randomUUID();
            String persistent = principal + "-persistent";
            String expired = principal + "-expired";
            int deadline = Math.toIntExact(Instant.now().getEpochSecond() + 90);
            assertThrows(IllegalArgumentException.class,
                    () -> store.migrateLegacyWindowFromSnapshot(principal, -1, deadline));
            assertThrows(IllegalArgumentException.class,
                    () -> store.migrateLegacyWindowFromSnapshot(principal, 3, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> store.migrateLegacyWindowFromSnapshot(principal, 3, Integer.MAX_VALUE + 1L));
            assertTrue(store.migrateLegacyWindowFromSnapshot(principal, 3, deadline));
            assertFalse(store.migrateLegacyWindowFromSnapshot(principal, 6, deadline + 5L));
            assertTrue(store.migrateLegacyWindowFromSnapshot(persistent, 0, 0));
            assertFalse(store.migrateLegacyWindowFromSnapshot(expired, 7, deadline - 91L));
            assertNull(firstClient.get("shiro-retry:" + digest(namespace) + ":" + digest(expired),
                    new StringTranscoder()));

            Attempt next = store.reserve(principal);
            Attempt persistentAttempt = store.reserve(persistent);
            assertEquals(4, next.getCount());
            assertEquals(1, persistentAttempt.getCount());
            String target = "shiro-retry:" + digest(namespace) + ":" + digest(principal);
            String persistentKey = "shiro-retry:" + digest(namespace) + ":" + digest(persistent);
            assertEquals(deadline + ":4:" + next.getWindowId(), firstClient.get(target, new StringTranscoder()));
            assertStoredDeadlines(target, deadline, persistentKey);
        }
    }

    @Test
    public void importsLegacyWindowsAfterBinarySourceWarmRestart() throws Exception {
        String sourcePort = System.getProperty("shiro.memcached.warm.test.port");
        String container = System.getProperty("shiro.memcached.warm.test.container");
        assumeTrue("Supply a dedicated warm-restart Memcached fixture",
                Objects.nonNull(sourcePort) && Objects.nonNull(container));
        InetSocketAddress source = AddrUtil.getOneAddress("127.0.0.1:" + sourcePort);
        SerializationUtils.init("java", new Properties());
        SerializingTranscoder transcoder = new SerializingTranscoder();
        String principal = "warm-" + UUID.randomUUID();
        String persistent = principal + "-persistent";
        String expiring = principal + "-expiring";
        int deadline = Math.toIntExact(Instant.now().getEpochSecond() + 60);
        byte[] counter = SerializationUtils.serialize(new AtomicInteger(3));
        byte[] zero = SerializationUtils.serialize(new AtomicInteger(0));
        MemcachedClient legacy = client("127.0.0.1:" + sourcePort);
        try {
            assertTrue(legacy.set(REGION + ":" + principal, deadline, counter, transcoder));
            assertTrue(legacy.set(REGION + ":" + persistent, 0, zero, transcoder));
            assertTrue(legacy.set(REGION + ":" + expiring, 1, counter, transcoder));
        } finally {
            legacy.shutdown();
        }
        Process stop = new ProcessBuilder("docker", "exec", container, "sh", "-ec",
                "kill -USR1 \"$(cat /state/source.pid)\"").inheritIO().start();
        assertTrue("Graceful stop command timed out", stop.waitFor(10, TimeUnit.SECONDS));
        assertEquals("Graceful stop command failed", 0, stop.exitValue());
        awaitMetaListener(source);

        J2CacheConfig config = config(true, "30s");
        try (CacheChannel channel = J2CacheTestChannel.open(config)) {
            J2CacheMemcachedCredentialRetryStore store = store(channel, config, firstClient);
            assertEquals(deadline, MemcachedLegacyWindow.read(source, REGION + ":" + principal, 3000).getDeadline());
            assertTrue(store.migrateLegacyWindow(principal, source));
            assertFalse(store.migrateLegacyWindow(principal, source));
            assertTrue(store.migrateLegacyWindow(persistent, source));
            assertFalse(store.migrateLegacyWindow(expiring, source));
            Attempt next = store.reserve(principal);
            assertEquals(4, next.getCount());
            String target = "shiro-retry:" + digest(namespace) + ":" + digest(principal);
            assertEquals(deadline + ":4:" + next.getWindowId(), firstClient.get(target, new StringTranscoder()));
            Attempt persistentAttempt = store.reserve(persistent);
            assertEquals(1, persistentAttempt.getCount());
            String persistentKey = "shiro-retry:" + digest(namespace) + ":" + digest(persistent);
            assertEquals("0:1:" + persistentAttempt.getWindowId(), firstClient.get(persistentKey, new StringTranscoder()));
            assertEquals(3, MemcachedLegacyWindow.read(source, REGION + ":" + principal, 3000).getCount());
            assertStoredDeadlines(target, deadline, persistentKey);
        }
    }

    private void assertStoredDeadlines(String target, int deadline, String persistentKey) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(AddrUtil.getOneAddress(endpoint), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("lru_crawler metadump hash\r\n".getBytes(StandardCharsets.US_ASCII));
            BufferedReader reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            Set<String> keysAtExpectedExpiry = new HashSet<>();
            String line;
            while (Objects.nonNull(line = reply.readLine()) && !"END".equals(line)) {
                String entry = URLDecoder.decode(line, StandardCharsets.UTF_8.name());
                if (entry.startsWith("key=" + target + " exp=" + deadline + " ")) {
                    keysAtExpectedExpiry.add(target);
                }
                if (entry.startsWith("key=" + persistentKey + " exp=-1 ")) {
                    keysAtExpectedExpiry.add(persistentKey);
                }
            }
            assertEquals(2, keysAtExpectedExpiry.size());
            assertTrue(keysAtExpectedExpiry.contains(target));
            assertTrue(keysAtExpectedExpiry.contains(persistentKey));
        }
    }

    private static void awaitMetaListener(InetSocketAddress address) throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(address, 250);
                socket.setSoTimeout(250);
                socket.getOutputStream().write("version\r\n".getBytes(StandardCharsets.US_ASCII));
                BufferedReader reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String version = reply.readLine();
                if (Objects.nonNull(version) && version.startsWith("VERSION ")) {
                    return;
                }
            } catch (IOException ignored) {
                // Connections can fail while the Memcached process restarts.
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Warm-restarted Memcached did not open its Meta Text listener");
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
        return client(endpoint);
    }

    private MemcachedClient client(String server) throws Exception {
        var builder = new XMemcachedClientBuilder(AddrUtil.getAddresses(server));
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
