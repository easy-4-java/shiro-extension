package org.apache.shiro.biz.cache.j2cache;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
import org.apache.shiro.codec.Hex;

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
    private final String legacyRegion;
    private final boolean legacySanitizeKeys;

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
        this.legacyRegion = region;
        this.legacySanitizeKeys = Boolean.parseBoolean(config.getL2CacheProperties().getProperty("sanitizeKeys"));
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
                    if (client.add(key, initial.expiresAt, initial.encode(), STRINGS)) {
                        return initial.attempt();
                    }
                } else {
                    Window window = Window.decode(current.getValue());
                    Window next;
                    if (window.expiresAt > 0 && Instant.now().getEpochSecond() >= window.expiresAt) {
                        next = newWindow();
                    } else {
                        if (window.count == Integer.MAX_VALUE) {
                            return window.attempt();
                        }
                        next = new Window(window.windowId, window.count + 1, window.expiresAt);
                    }
                    if (client.cas(key, next.expiresAt, next.encode(), STRINGS, current.getCas())) {
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
                if (Objects.isNull(current) || !windowId.equals(Window.decode(current.getValue()).windowId)) {
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

    /**
     * Copies a legacy counter and its deadline during an exclusive cutover.
     * Pause old and new clients and drain outstanding writes before importing.
     *
     * @param principal original string principal in the J2Cache region
     * @param source server owning the legacy key, with the Memcached Meta Text protocol enabled
     * @return true when the counter was copied to an empty destination
     */
    public boolean migrateLegacyWindow(String principal, InetSocketAddress source) {
        Objects.requireNonNull(source, "source");
        int timeout = (int) Math.max(1, Math.min(client.getOpTimeout(), Integer.MAX_VALUE));
        return migrateLegacyWindow(principal, key -> MemcachedLegacyWindow.read(source, key, timeout));
    }

    /**
     * Copies a legacy counter through a dedicated, already authenticated Meta Text connection.
     * The caller owns the socket and must close it after use, including after errors.
     * Pause retry clients and drain outstanding writes before importing.
     *
     * @param principal original string principal in the J2Cache region
     * @param source connected socket to the server owning the legacy key, with a read timeout
     * @return true when the counter was copied to an empty destination
     */
    public boolean migrateLegacyWindowFromMetaConnection(String principal, Socket source) {
        Objects.requireNonNull(source, "source");
        if (!source.isConnected() || source.isClosed()) {
            throw new IllegalArgumentException("The Memcached source connection must be open");
        }
        return migrateLegacyWindow(principal, key -> {
            if (source.getSoTimeout() <= 0) {
                throw new IllegalArgumentException("The Memcached source connection requires a read timeout");
            }
            return MemcachedLegacyWindow.read(source, key);
        });
    }

    /**
     * Imports a verified legacy counter and its original deadline during an exclusive cutover.
     * Pause retry clients and drain outstanding writes before taking the snapshot. Supply the
     * count and deadline from the same source entry and use zero only for a persistent entry.
     *
     * @param principal original string principal in the J2Cache region
     * @param count original nonnegative retry count
     * @param deadlineEpochSecond original absolute expiry, or zero for a persistent entry
     * @return true when an active snapshot was copied to an empty destination
     */
    public boolean migrateLegacyWindowFromSnapshot(String principal, int count, long deadlineEpochSecond) {
        if (count < 0 || deadlineEpochSecond < 0 || deadlineEpochSecond > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid Memcached snapshot retry count or expiry");
        }
        MemcachedLegacyWindow legacy = new MemcachedLegacyWindow(count, (int) deadlineEpochSecond);
        return migrateLegacyWindow(principal, ignored -> legacy);
    }

    private boolean migrateLegacyWindow(String principal, LegacyRead source) {
        Objects.requireNonNull(principal, "principal");
        try {
            String legacyKey = legacyRegion + ":" + principal;
            if (legacySanitizeKeys) {
                legacyKey = URLEncoder.encode(legacyKey, StandardCharsets.UTF_8.name());
            }
            MemcachedLegacyWindow legacy = source.read(legacyKey);
            if (Objects.isNull(legacy) || (legacy.getDeadline() > 0 && legacy.getDeadline() <= Instant.now().getEpochSecond())) {
                return false;
            }
            Window window = new Window(UUID.randomUUID().toString(), legacy.getCount(), legacy.getDeadline());
            return client.add(key(principal), window.expiresAt, window.encode(), STRINGS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CacheException("Interrupted while migrating a Memcached retry window", exception);
        } catch (IOException | TimeoutException | MemcachedException exception) {
            throw new CacheException("Failed to migrate a Memcached retry window", exception);
        }
    }

    @FunctionalInterface
    private interface LegacyRead {

        MemcachedLegacyWindow read(String key) throws IOException;
    }

    private Window newWindow() {
        return new Window(UUID.randomUUID().toString(), 1, initialDeadline());
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
            return Hex.encodeToString(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class Window {

        private final String windowId;
        private final int count;
        private final int expiresAt;

        private Window(String windowId, int count, int expiresAt) {
            if (count < 0 || expiresAt < 0) {
                throw new CacheException("Invalid Memcached retry count or expiry");
            }
            this.windowId = windowId;
            this.count = count;
            this.expiresAt = expiresAt;
        }

        private Attempt attempt() {
            return new Attempt(windowId, count);
        }

        private String encode() {
            return expiresAt + ":" + count + ":" + windowId;
        }

        private static Window decode(String encoded) {
            String[] fields = encoded.split(":", 3);
            if (fields.length != 3) {
                throw new CacheException("Unexpected Memcached retry window format");
            }
            return new Window(fields[2], Integer.parseInt(fields[1]), Integer.parseInt(fields[0]));
        }
    }
}
