package org.apache.shiro.biz.cache.redisson;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.cache.redis.RedisCredentialRetryStore;
import org.apache.shiro.cache.CacheException;
import org.redisson.RedissonObject;
import org.redisson.api.RMapCache;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.handler.State;

/**
 * Shared retry state using Redisson's Redis connection and the configured TTL and idle period.
 */
public final class RedissonCredentialRetryStore implements CredentialRetryStore {

    private final RedisCredentialRetryStore delegate;
    private final RedissonClient client;

    // Redisson 3.12.5 stores the idle period with the value and each deadline in its own sorted set.
    private static final String SNAPSHOT_LEGACY = """
            local value = redis.call('HGET', KEYS[1], ARGV[1])
            if not value then return {} end
            local idle, payload = struct.unpack('dLc0', value)
            local ttlDeadline = tonumber(redis.call('ZSCORE', KEYS[2], ARGV[1]) or '0')
            local idleDeadline = 0
            if idle > 0 then
              idleDeadline = tonumber(redis.call('ZSCORE', KEYS[3], ARGV[1]) or '0')
              if idleDeadline == 0 then return redis.error_reply('Missing Redisson idle deadline') end
            end
            local mapTtl = redis.call('PTTL', KEYS[1])
            if mapTtl >= 0 then
              local time = redis.call('TIME')
              local mapDeadline = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) + mapTtl
              if ttlDeadline == 0 or mapDeadline < ttlDeadline then ttlDeadline = mapDeadline end
            end
            return {payload, ttlDeadline, idleDeadline, idle}
            """;

    /**
     * @param client the configured Redisson client
     * @param namespace dedicated retry namespace shared by all clients
     * @param principalKey stable principal encoding shared by all clients
     * @param ttl configured maximum lifetime, or zero for no TTL
     * @param maxIdle configured idle timeout, or zero for no idle timeout
     */
    public RedissonCredentialRetryStore(RedissonClient client, String namespace,
                                       Function<Object, String> principalKey, Duration ttl, Duration maxIdle) {
        this.client = Objects.requireNonNull(client, "client");
        RScript scripts = client.getScript(StringCodec.INSTANCE);
        this.delegate = new RedisCredentialRetryStore(
                (script, keys, args) -> scripts.eval(keys.get(0), RScript.Mode.READ_WRITE, script,
                        RScript.ReturnType.VALUE, List.<Object>copyOf(keys), args.toArray()),
                namespace, principalKey, ttl, maxIdle);
    }

    @Override
    public Attempt reserve(Object principal) {
        return delegate.reserve(principal);
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        delegate.clearIfCurrent(principal, windowId);
    }

    /**
     * Copies a Redisson 3.12.5 map-cache counter during an exclusive cutover.
     * Pause old and new clients and drain writes before importing.
     *
     * @param source original retry map cache on the same Redis deployment
     * @param principal original map key and principal for the destination
     * @return true if a live legacy window was copied into an empty destination
     */
    public <K> boolean migrateLegacyWindow(RMapCache<K, ?> source, K principal) {
        String name = Objects.requireNonNull(source, "source").getName();
        Objects.requireNonNull(principal, "principal");
        Codec codec = source.getCodec();
        try {
            ByteBuf key = codec.getMapKeyEncoder().encode(principal);
            List<?> snapshot;
            try {
                snapshot = client.getScript(ByteArrayCodec.INSTANCE).eval(name, RScript.Mode.READ_WRITE,
                        SNAPSHOT_LEGACY, RScript.ReturnType.MULTI, List.of(name,
                                RedissonObject.prefixName("redisson__timeout__set", name),
                                RedissonObject.prefixName("redisson__idle__set", name)),
                        ByteBufUtil.getBytes(key));
            } finally {
                key.release();
            }
            if (snapshot.isEmpty()) {
                return false;
            }
            ByteBuf payload = Unpooled.wrappedBuffer((byte[]) snapshot.get(0));
            try {
                Object counter = codec.getMapValueDecoder().decode(payload, new State());
                if (!(counter instanceof AtomicInteger)) {
                    throw new CacheException("Redisson legacy retry entry must contain an AtomicInteger");
                }
                return delegate.importWindow(principal, ((AtomicInteger) counter).get(),
                        (Long) snapshot.get(1), (Long) snapshot.get(2), Duration.ofMillis((Long) snapshot.get(3)));
            } finally {
                payload.release();
            }
        } catch (IOException exception) {
            throw new CacheException("Unable to decode the Redisson legacy retry entry", exception);
        }
    }
}
