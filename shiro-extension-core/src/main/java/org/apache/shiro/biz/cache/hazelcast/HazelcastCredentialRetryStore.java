package org.apache.shiro.biz.cache.hazelcast;

import com.hazelcast.core.EntryView;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;
import com.hazelcast.map.IMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.cache.CacheException;

/**
 * Shared retry state in a dedicated Hazelcast 5.2+ IMap. The partition owner
 * reserves an attempt atomically, using the map's TTL and max-idle policy.
 *
 * @param <K> cache key type
 */
public final class HazelcastCredentialRetryStore<K> implements CredentialRetryStore {

    private static final int MAX_CONFLICTS = 128;

    private final IMap<K, Attempt> map;
    private final Function<Object, K> principalKey;

    /**
     * @param map dedicated retry map. Cluster members must have this module on their classpath.
     * @param principalKey consistent principal key encoding for all callers sharing the map
     */
    public HazelcastCredentialRetryStore(IMap<K, Attempt> map, Function<Object, K> principalKey) {
        this.map = Objects.requireNonNull(map, "map");
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
    }

    @Override
    public Attempt reserve(Object principal) {
        return map.executeOnKey(key(principal), new Reserve<>(UUID.randomUUID().toString()));
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        K key = key(principal);
        Objects.requireNonNull(windowId, "windowId");
        for (int conflict = 0; conflict < MAX_CONFLICTS; conflict++) {
            EntryView<K, Attempt> entry = map.getEntryView(key);
            if (Objects.isNull(entry) || !windowId.equals(entry.getValue().getWindowId())
                    || map.remove(key, entry.getValue())) {
                return;
            }
        }
        throw new CacheException("Unable to clear credential retry state within the conflict limit");
    }

    private K key(Object principal) {
        return Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
    }

    private static final class Reserve<K> implements EntryProcessor<K, Attempt, Attempt> {

        private static final long serialVersionUID = 1L;

        private final String initialWindowId;
        private transient Attempt updated;

        private Reserve(String initialWindowId) {
            this.initialWindowId = initialWindowId;
        }

        @Override
        public Attempt process(Map.Entry<K, Attempt> entry) {
            updated = null;
            Attempt current = entry.getValue();
            if (Objects.isNull(current)) {
                updated = new Attempt(initialWindowId, 1);
            } else if (current.getCount() == Integer.MAX_VALUE) {
                return current;
            } else {
                updated = new Attempt(current.getWindowId(), current.getCount() + 1);
            }
            ((ExtendedMapEntry<K, Attempt>) entry).setValueWithoutChangingExpiryTime(updated);
            return updated;
        }

        @Override
        public EntryProcessor<K, Attempt, Attempt> getBackupProcessor() {
            if (Objects.isNull(updated)) {
                return null;
            }
            // Replicate the primary's result even if the backup entry was absent.
            return new CopyReservation<>(updated);
        }
    }

    private static final class CopyReservation<K> implements EntryProcessor<K, Attempt, Attempt> {

        private static final long serialVersionUID = 1L;

        private final Attempt attempt;

        private CopyReservation(Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public Attempt process(Map.Entry<K, Attempt> entry) {
            ((ExtendedMapEntry<K, Attempt>) entry).setValueWithoutChangingExpiryTime(attempt);
            return attempt;
        }
    }
}
