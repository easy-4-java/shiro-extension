package org.apache.shiro.biz.cache.hazelcast;

import com.hazelcast.core.EntryView;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.ExtendedMapEntry;
import com.hazelcast.map.IMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final IMap<K, Object> map;
    private final Function<Object, K> principalKey;

    /**
     * @param map dedicated retry map. Legacy retry maps can be reused after all legacy clients stop.
     *            Cluster members must have this module on their classpath.
     * @param principalKey consistent principal key encoding for all callers sharing the map
     */
    @SuppressWarnings("unchecked")
    public HazelcastCredentialRetryStore(IMap<K, ?> map, Function<Object, K> principalKey) {
        // Hazelcast IMap has no runtime value type. The processor accepts both retry formats.
        this.map = (IMap<K, Object>) Objects.requireNonNull(map, "map");
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
            EntryView<K, Object> entry = map.getEntryView(key);
            if (Objects.isNull(entry)) {
                return;
            }
            Object value = entry.getValue();
            if (!(value instanceof Attempt) || !windowId.equals(((Attempt) value).getWindowId())
                    || map.remove(key, value)) {
                return;
            }
        }
        throw new CacheException("Unable to clear credential retry state within the conflict limit");
    }

    private K key(Object principal) {
        return Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
    }

    private static final class Reserve<K> implements EntryProcessor<K, Object, Attempt> {

        private static final long serialVersionUID = 1L;

        private final String initialWindowId;
        private transient Attempt updated;

        private Reserve(String initialWindowId) {
            this.initialWindowId = initialWindowId;
        }

        @Override
        public Attempt process(Map.Entry<K, Object> entry) {
            updated = null;
            Object value = entry.getValue();
            if (Objects.isNull(value)) {
                updated = new Attempt(initialWindowId, 1);
            } else if (value instanceof AtomicInteger) {
                int count = ((AtomicInteger) value).get();
                if (count < 0) {
                    throw new CacheException("Legacy credential retry count is negative");
                }
                updated = new Attempt(initialWindowId, count == Integer.MAX_VALUE ? count : count + 1);
            } else {
                Attempt current = (Attempt) value;
                if (current.getCount() == Integer.MAX_VALUE) {
                    return current;
                }
                updated = new Attempt(current.getWindowId(), current.getCount() + 1);
            }
            ((ExtendedMapEntry<K, Object>) entry).setValueWithoutChangingExpiryTime(updated);
            return updated;
        }

        @Override
        public EntryProcessor<K, Object, Attempt> getBackupProcessor() {
            if (Objects.isNull(updated)) {
                return null;
            }
            // Replicate the primary's result even if the backup entry was absent.
            return new CopyReservation<>(updated);
        }
    }

    private static final class CopyReservation<K> implements EntryProcessor<K, Object, Attempt> {

        private static final long serialVersionUID = 1L;

        private final Attempt attempt;

        private CopyReservation(Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public Attempt process(Map.Entry<K, Object> entry) {
            ((ExtendedMapEntry<K, Object>) entry).setValueWithoutChangingExpiryTime(attempt);
            return attempt;
        }
    }
}
