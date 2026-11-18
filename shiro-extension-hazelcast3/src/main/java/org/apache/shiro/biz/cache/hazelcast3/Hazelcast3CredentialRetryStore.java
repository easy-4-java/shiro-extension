package org.apache.shiro.biz.cache.hazelcast3;

import com.hazelcast.core.EntryView;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;

/**
 * Shared retry state in a dedicated Hazelcast 3.12 IMap. The map's key lock
 * guards each reservation and reset.
 *
 * @param <K> cache key type
 */
public final class Hazelcast3CredentialRetryStore<K> implements CredentialRetryStore {

    private static final long UNSET_DEADLINE = 0;
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    private final IMap<K, Window> map;
    private final long maxIdleMillis;
    private final Function<Object, K> principalKey;

    /**
     * @param hazelcast member or thin client connected to the retry map's cluster
     * @param mapName dedicated retry map managed exclusively by this store, with this module on all members
     * @param maxIdleSeconds the map's configured max idle. The 3.12 client wire format omits this setting
     * @param principalKey stable principal encoding shared by application instances
     */
    public Hazelcast3CredentialRetryStore(HazelcastInstance hazelcast, String mapName, int maxIdleSeconds,
                                          Function<Object, K> principalKey) {
        if (maxIdleSeconds < 0) {
            throw new IllegalArgumentException("The Hazelcast map max idle must be nonnegative");
        }
        this.map = Objects.requireNonNull(hazelcast, "hazelcast").getMap(mapName);
        this.maxIdleMillis = TimeUnit.SECONDS.toMillis(maxIdleSeconds);
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
    }

    @Override
    public Attempt reserve(Object principal) {
        K key = key(principal);
        map.lock(key);
        try {
            EntryView<K, Window> entry = map.getEntryView(key);
            long now = System.currentTimeMillis();
            if (Objects.isNull(entry)) {
                return startWindow(key);
            }
            long deadline = ttlDeadline(entry);
            if (expired(entry, deadline, now)) {
                // Hazelcast suspends expiry on locked keys. Reinsert with the map defaults.
                map.delete(key);
                return startWindow(key);
            }

            Attempt current = entry.getValue().attempt;
            Attempt next = current.getCount() == Integer.MAX_VALUE
                    ? current : new Attempt(current.getWindowId(), current.getCount() + 1);
            // Hazelcast 3.12 stores creation, update and TTL metadata at whole-second precision.
            long remainingSeconds = deadline == NO_DEADLINE ? 0
                    : TimeUnit.MILLISECONDS.toSeconds(deadline) - TimeUnit.MILLISECONDS.toSeconds(now);
            map.set(key, new Window(next, deadline), remainingSeconds, TimeUnit.SECONDS);
            return next;
        } finally {
            map.unlock(key);
        }
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        K key = key(principal);
        Objects.requireNonNull(windowId, "windowId");
        map.lock(key);
        try {
            EntryView<K, Window> entry = map.getEntryView(key);
            if (Objects.nonNull(entry) && windowId.equals(entry.getValue().attempt.getWindowId())) {
                map.delete(key);
            }
        } finally {
            map.unlock(key);
        }
    }

    private Attempt startWindow(K key) {
        Attempt initial = new Attempt(UUID.randomUUID().toString(), 1);
        map.set(key, new Window(initial, UNSET_DEADLINE));
        return initial;
    }

    private K key(Object principal) {
        return Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
    }

    private boolean expired(EntryView<K, Window> entry, long deadline, long now) {
        long lastActivity = Math.max(entry.getCreationTime(), entry.getLastAccessTime());
        return deadline <= now || (maxIdleMillis > 0 && now - lastActivity >= maxIdleMillis);
    }

    private static long ttlDeadline(EntryView<?, Window> entry) {
        long saved = entry.getValue().ttlDeadline;
        if (saved != UNSET_DEADLINE) {
            return saved;
        }

        long ttl = entry.getTtl();
        return ttl <= 0 || ttl == NO_DEADLINE ? NO_DEADLINE : entry.getCreationTime() + ttl;
    }

    private static final class Window implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Attempt attempt;
        private final long ttlDeadline;

        private Window(Attempt attempt, long ttlDeadline) {
            this.attempt = attempt;
            this.ttlDeadline = ttlDeadline;
        }
    }
}
