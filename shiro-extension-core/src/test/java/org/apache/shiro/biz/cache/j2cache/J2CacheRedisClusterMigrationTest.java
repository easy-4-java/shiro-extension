package org.apache.shiro.biz.cache.j2cache;

import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import net.oschina.j2cache.Level2Cache;
import net.oschina.j2cache.util.SerializationUtils;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.junit.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.util.JedisClusterCRC16;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class J2CacheRedisClusterMigrationTest {

    private static final String REGION = "credential-retry";
    private static final String PRINCIPAL = "principal";
    private static final String DESTINATION = "{retry-current}:";
    private static final String LEGACY = "{retry-legacy}:";

    @Test
    public void jedisMigratesGenericAndHashCountersAcrossPrimaries() throws Exception {
        String nodes = nodes();
        // Jedis 2.9's parseString rewrites loopback to the machine's primary address.
        Set<HostAndPort> addresses = redisUris(nodes).stream()
                .map(uri -> new HostAndPort(uri.getHost(), uri.getPort()))
                .collect(Collectors.toSet());
        try (JedisCluster jedis = new JedisCluster(addresses)) {
            verifyLayouts(nodes, "redis", (channel, config, namespace, commands, sourceCommands) -> {
                J2CacheRedisCredentialRetryStore store = new J2CacheRedisCredentialRetryStore(
                        channel, config, REGION, namespace, Object::toString, jedis);
                return new Migration(store, store::migrateLegacyWindow);
            });
        }
    }

    @Test
    public void lettuceMigratesGenericAndHashCountersAcrossPrimaries() throws Exception {
        verifyLayouts(nodes(), "lettuce", (channel, config, namespace, commands, sourceCommands) -> {
            J2CacheLettuceCredentialRetryStore store = new J2CacheLettuceCredentialRetryStore(
                    channel, config, REGION, namespace, Object::toString, commands, sourceCommands);
            return new Migration(store, store::migrateLegacyWindow);
        });
    }

    private void verifyLayouts(String nodes, String provider, StoreFactory factory) throws Exception {
        SerializationUtils.init("java", new Properties());
        RedisClusterClient client = RedisClusterClient.create(redisUris(nodes));
        try (StatefulRedisClusterConnection<String, String> connection = client.connect();
             StatefulRedisClusterConnection<byte[], byte[]> sourceConnection = client.connect(ByteArrayCodec.INSTANCE)) {
            RedisAdvancedClusterCommands<String, String> commands = connection.sync();
            for (String storage : new String[] {"generic", "hash"}) {
                String id = UUID.randomUUID().toString();
                String namespace = DESTINATION + id;
                String sourceNamespace = LEGACY + "l\u00e9gacy:" + id;
                String sourceKey = sourceNamespace + ":" + REGION
                        + ("hash".equals(storage) ? "" : ":" + PRINCIPAL);
                Charset encoding = "redis".equals(provider) && "generic".equals(storage)
                        ? StandardCharsets.UTF_8 : Charset.defaultCharset();
                byte[] sourceKeyBytes = sourceKey.getBytes(encoding);
                String destinationKey = namespace + ":" + PRINCIPAL;
                String sourceOwner = client.getPartitions().getPartitionBySlot(JedisClusterCRC16.getSlot(sourceKeyBytes))
                        .getNodeId();
                String destinationOwner =
                        client.getPartitions().getPartitionBySlot(JedisClusterCRC16.getSlot(destinationKey)).getNodeId();
                assertNotEquals("Legacy and retry keys must lie on different primaries", sourceOwner, destinationOwner);
                J2CacheConfig config = config(nodes, provider, storage, sourceNamespace);
                try (CacheChannel channel = J2CacheTestChannel.open(config)) {
                    channel.set(REGION, PRINCIPAL, new AtomicInteger(1));
                    Level2Cache source = (Level2Cache) channel.getL2Provider().buildCache(REGION, null);
                    source.put(PRINCIPAL, new AtomicInteger(3), 45L);
                    assertEquals(1, ((AtomicInteger) channel.get(REGION, PRINCIPAL).getValue()).get());
                    // Give both layouts a finite source deadline, including persistent hash regions.
                    assertTrue("Failed to set expiry on persisted " + storage + " key",
                            sourceConnection.sync().pexpire(sourceKeyBytes, 45_000L));
                    long deadline = deadline(sourceConnection.sync(), sourceKeyBytes);
                    assertTrue(deadline > System.currentTimeMillis());
                    Migration migration = factory.open(channel, config, namespace, commands, sourceConnection.sync());
                    assertTrue(migration.importWindow.test(PRINCIPAL));
                    assertFalse(migration.importWindow.test(PRINCIPAL));
                    assertEquals(deadline, deadline(commands, destinationKey));
                    assertEquals(4, migration.store.reserve(PRINCIPAL).getCount());
                    assertEquals(deadline, deadline(commands, destinationKey));
                    assertEquals(3, ((AtomicInteger) source.get(PRINCIPAL)).get());
                    assertEquals(deadline, deadline(sourceConnection.sync(), sourceKeyBytes));
                }
            }
        } finally {
            client.shutdown();
        }
    }

    private static List<RedisURI> redisUris(String nodes) {
        return Arrays.stream(nodes.split(",")).map(node -> RedisURI.create("redis://" + node))
                .collect(Collectors.toList());
    }

    private static <K, V> long deadline(RedisAdvancedClusterCommands<K, V> commands, K key) {
        return commands.eval("return redis.call('PEXPIRETIME', KEYS[1])", ScriptOutputType.INTEGER, key);
    }

    private static String nodes() {
        String nodes = System.getProperty("shiro.redis.test.cluster.nodes");
        assumeTrue("Supply an isolated Redis cluster", Objects.nonNull(nodes));
        String expectedCharset = System.getProperty("shiro.redis.test.expectedCharset");
        if (Objects.nonNull(expectedCharset)) {
            assertEquals(Charset.forName(expectedCharset), Charset.defaultCharset());
        }
        return nodes;
    }

    private static J2CacheConfig config(String nodes, String provider, String storage, String sourceNamespace) {
        J2CacheConfig config = new J2CacheConfig();
        config.setL1CacheName("caffeine");
        config.setL2CacheName(provider);
        config.setSyncTtlToRedis(true);
        config.getL1CacheProperties().setProperty("region." + REGION, "100, 45s");
        config.getL2CacheProperties().setProperty("hosts", nodes);
        config.getL2CacheProperties().setProperty("mode", "cluster");
        config.getL2CacheProperties().setProperty("scheme", "redis-cluster");
        config.getL2CacheProperties().setProperty("password", "");
        config.getL2CacheProperties().setProperty("namespace", sourceNamespace);
        config.getL2CacheProperties().setProperty("storage", storage);
        return config;
    }

    @FunctionalInterface
    private interface StoreFactory {
        Migration open(CacheChannel channel, J2CacheConfig config, String namespace,
                       RedisAdvancedClusterCommands<String, String> commands,
                       RedisAdvancedClusterCommands<byte[], byte[]> sourceCommands);
    }

    private static final class Migration {
        private final CredentialRetryStore store;
        private final Predicate<String> importWindow;

        private Migration(CredentialRetryStore store, Predicate<String> importWindow) {
            this.store = store;
            this.importWindow = importWindow;
        }
    }
}
