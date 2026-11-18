package org.apache.shiro.biz.cache.j2cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import net.oschina.j2cache.memcached.XmemcachedCacheProvider;
import net.rubyeye.xmemcached.GetsResponse;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.exception.MemcachedException;
import net.rubyeye.xmemcached.transcoders.StringTranscoder;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.cache.CacheException;

/** Credential retry state in the Memcached second level of a J2Cache channel. */
public final class J2CacheMemcachedCredentialRetryStore implements CredentialRetryStore {

    private static final int MAX_RELATIVE_SECONDS = 30 * 24 * 60 * 60;
    private static final int EXPIRED_TIMESTAMP = MAX_RELATIVE_SECONDS + 1;
    private static final int MAX_CONFLICTS = 128;
    private static final StringTranscoder STRINGS = new StringTranscoder(StandardCharsets.UTF_8.name());

    private final MemcachedClient client;
    private final String keyPrefix;
    private final Function<Object, String> principalKey;
    private final long regionExpiry;

    /**
     * @param channel channel using the xmemcached L2 provider
     * @param config configuration used to create the channel, with L2 expiry synchronization enabled
     * @param region existing retry region whose expiry policy is reused
     * @param namespace dedicated retry namespace shared by all instances
     * @param principalKey stable principal encoding shared by all instances
     * @param client caller-managed client on the same Memcached servers, using the same key routing
     */
    public J2CacheMemcachedCredentialRetryStore(CacheChannel channel, J2CacheConfig config, String region,
                                               String namespace, Function<Object, String> principalKey,
                                               MemcachedClient client) {
        if (!(channel.getL2Provider() instanceof XmemcachedCacheProvider) || !config.isSyncTtlToRedis()) {
            throw new IllegalArgumentException("Memcached retry state requires an L2 provider with TTL synchronization");
        }
        this.client = Objects.requireNonNull(client, "client");
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
        this.keyPrefix = "shiro-retry:" + digest(Objects.requireNonNull(namespace, "namespace")) + ":";
        this.regionExpiry = J2CacheRetryExpiry.regionSeconds(channel, region);
        if (regionExpiry < 0 || regionExpiry > Integer.MAX_VALUE
                || (regionExpiry > MAX_RELATIVE_SECONDS && regionExpiry <= Instant.now().getEpochSecond())) {
            throw new IllegalArgumentException("The retry region has an invalid Memcached expiry");
        }
    }

    @Override
    public Attempt reserve(Object principal) {
        String key = key(principal);
        try {
            for (int conflict = 0; conflict < MAX_CONFLICTS; conflict++) {
                GetsResponse<String> current = client.gets(key, STRINGS);
                if (Objects.isNull(current)) {
                    Window initial = newWindow();
                    if (client.add(key, initial.expiresAt(), initial.encode(), STRINGS)) {
                        return initial.attempt();
                    }
                } else {
                    Window window = Window.decode(current.getValue());
                    Window next;
                    if (window.expiresAt() > 0 && Instant.now().getEpochSecond() >= window.expiresAt()) {
                        next = newWindow();
                    } else {
                        Attempt attempt = window.attempt();
                        if (attempt.getCount() == Integer.MAX_VALUE) {
                            return attempt;
                        }
                        next = new Window(new Attempt(attempt.getWindowId(), attempt.getCount() + 1),
                                window.expiresAt());
                    }
                    if (client.cas(key, next.expiresAt(), next.encode(), STRINGS, current.getCas())) {
                        return next.attempt();
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CacheException("Interrupted while reserving a Memcached retry attempt", exception);
        } catch (TimeoutException | MemcachedException exception) {
            throw new CacheException("Failed to reserve a Memcached retry attempt", exception);
        }
        throw new CacheException("Unable to reserve a Memcached retry attempt within the conflict limit");
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        String key = key(principal);
        Objects.requireNonNull(windowId, "windowId");
        try {
            for (int conflict = 0; conflict < MAX_CONFLICTS; conflict++) {
                GetsResponse<String> current = client.gets(key, STRINGS);
                if (Objects.isNull(current) || !windowId.equals(Window.decode(current.getValue()).attempt().getWindowId())) {
                    return;
                }
                // A past absolute expiry makes the CAS remove only the version that was read.
                if (client.cas(key, EXPIRED_TIMESTAMP, current.getValue(), STRINGS, current.getCas())) {
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CacheException("Interrupted while clearing a Memcached retry window", exception);
        } catch (TimeoutException | MemcachedException exception) {
            throw new CacheException("Failed to clear a Memcached retry window", exception);
        }
        throw new CacheException("Unable to clear a Memcached retry window within the conflict limit");
    }

    private Window newWindow() {
        return new Window(new Attempt(UUID.randomUUID().toString(), 1), initialDeadline());
    }

    private int initialDeadline() {
        if (regionExpiry == 0) {
            return 0;
        }
        long now = Instant.now().getEpochSecond();
        long expiry = regionExpiry <= MAX_RELATIVE_SECONDS ? now + regionExpiry : regionExpiry;
        if (expiry <= now || expiry > Integer.MAX_VALUE) {
            throw new CacheException("The retry region's Memcached expiry is out of range");
        }
        return (int) expiry;
    }

    private String key(Object principal) {
        return keyPrefix + digest(Objects.requireNonNull(principalKey.apply(principal), "encoded principal"));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Window(Attempt attempt, int expiresAt) {

        private String encode() {
            return expiresAt + ":" + attempt.getCount() + ":" + attempt.getWindowId();
        }

        private static Window decode(String encoded) {
            String[] fields = encoded.split(":", 3);
            if (fields.length != 3) {
                throw new CacheException("Unexpected Memcached retry window format");
            }
            return new Window(new Attempt(fields[2], Integer.parseInt(fields[1])), Integer.parseInt(fields[0]));
        }
    }
}
