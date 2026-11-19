package org.apache.shiro.biz.cache.ehcache;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.cache.CacheException;
import org.ehcache.Cache;
import org.ehcache.expiry.ExpiryPolicy;

/**
 * Credential retry state backed by native Ehcache 3 conditional operations.
 * A clustered cache must provide strong consistency for its shared retry region.
 *
 * @param <K> cache key type
 */
public final class Ehcache3CredentialRetryStore<K> implements CredentialRetryStore {

    private static final int MAX_CONFLICTS = 128;

    private final Cache<K, Object> cache;
    private final Function<Object, K> principalKey;
    private final boolean usesTypedCounters;

    /**
     * @param cache dedicated retry cache configured with {@link #fixedWindowExpiry(Duration)}
     *              or Ehcache's eternal expiry. An AtomicInteger-typed region must
     *              preserve the serializable counter subtype during storage.
     * @param principalKey consistent principal key encoding for all callers sharing the cache
     */
    @SuppressWarnings("unchecked")
    public Ehcache3CredentialRetryStore(Cache<K, ?> cache, Function<Object, K> principalKey) {
        Objects.requireNonNull(cache, "cache");
        Class<?> valueType = cache.getRuntimeConfiguration().getValueType();
        this.usesTypedCounters = AtomicInteger.class.equals(valueType);
        if (!usesTypedCounters && !valueType.isAssignableFrom(Attempt.class)) {
            throw new IllegalArgumentException("Ehcache retry region must accept credential retry attempts");
        }
        this.cache = (Cache<K, Object>) cache;
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
        ExpiryPolicy<?, ?> expiry = cache.getRuntimeConfiguration().getExpiryPolicy();
        if (!(expiry instanceof FixedWindowExpiry) && !ExpiryPolicy.NO_EXPIRY.equals(expiry)) {
            throw new IllegalArgumentException("Retry cache must preserve its creation expiry");
        }
    }

    /**
     * Returns an expiry policy for a dedicated retry cache.
     *
     * @param window configured duration for a newly created retry window
     * @param <V> value type of the retry region
     * @return policy that retains the first deadline on reads and updates
     */
    public static <V> ExpiryPolicy<Object, V> fixedWindowExpiry(Duration window) {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Retry window must be positive");
        }
        return new FixedWindowExpiry<>(window);
    }

    @Override
    public Attempt reserve(Object principal) {
        K key = key(principal);
        for (int conflict = 0; conflict < MAX_CONFLICTS; conflict++) {
            Object value = cache.get(key);
            if (Objects.isNull(value)) {
                Attempt initial = firstAttempt();
                if (Objects.isNull(cache.putIfAbsent(key, storeValue(initial)))) {
                    return initial;
                }
            } else if (isMigratedZero(value)) {
                Attempt initial = firstAttempt();
                if (cache.replace(key, value, storeValue(initial))) {
                    return initial;
                }
            } else {
                Attempt current = storedAttempt(value);
                if (Objects.isNull(current)) {
                    throw new CacheException("Legacy Ehcache retry entry requires migration before reservation");
                }
                if (current.getCount() == Integer.MAX_VALUE) {
                    return current;
                }
                Attempt next = new Attempt(current.getWindowId(), current.getCount() + 1);
                if (cache.replace(key, value, storeValue(next))) {
                    return next;
                }
            }
        }
        throw new CacheException("Unable to reserve credential attempt within the conflict limit");
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        K key = key(principal);
        Objects.requireNonNull(windowId, "windowId");
        for (int conflict = 0; conflict < MAX_CONFLICTS; conflict++) {
            Object value = cache.get(key);
            Attempt current = storedAttempt(value);
            if (Objects.isNull(current) || !windowId.equals(current.getWindowId()) || cache.remove(key, value)) {
                return;
            }
        }
        throw new CacheException("Unable to clear credential retry state within the conflict limit");
    }

    /**
     * Converts a persisted counter in place without restarting its expiry.
     * Pause all legacy and new retry clients, drain writes, and use a single migration writer.
     * The existing region must retain the actual type of stored values when serializing them.
     * A region typed as AtomicInteger stores the converted state in a serializable subtype.
     *
     * @param principal original principal in the Ehcache region
     * @return true when a live legacy counter was converted
     */
    public boolean migrateLegacyWindow(Object principal) {
        K key = key(principal);
        Object value = cache.get(key);
        if (!(value instanceof AtomicInteger) || value instanceof TypedCounter) {
            return false;
        }
        int count = ((AtomicInteger) value).get();
        if (count < 0) {
            throw new CacheException("Legacy credential retry count is negative");
        }
        Object migrated = count == 0
                ? (usesTypedCounters ? new TypedCounter(null, 0) : MigratedCounter.ZERO)
                : storeValue(new Attempt(UUID.randomUUID().toString(), count));
        // Serialized AtomicInteger copies cannot be compared reliably. Under exclusive cutover,
        // replacing a live entry preserves the existing expiry through the validated policy.
        return Objects.nonNull(cache.replace(key, migrated));
    }

    /**
     * Converts a live retry window back to a legacy counter while retaining its expiry.
     * Pause all retry clients and drain outstanding writes before restoring the region.
     *
     * @param principal original principal in the Ehcache region
     * @return true when a live retry window was restored
     */
    public boolean restoreLegacyWindow(Object principal) {
        if (!cache.getRuntimeConfiguration().getValueType().isAssignableFrom(AtomicInteger.class)) {
            throw new IllegalArgumentException("Ehcache retry region must accept legacy counters");
        }
        K key = key(principal);
        Object value = cache.get(key);
        Attempt attempt = storedAttempt(value);
        if (Objects.isNull(attempt) && !isMigratedZero(value)) {
            return false;
        }
        int count = Objects.nonNull(attempt) ? attempt.getCount() : 0;
        return cache.replace(key, value, new AtomicInteger(count));
    }

    private Object storeValue(Attempt attempt) {
        return usesTypedCounters ? new TypedCounter(attempt.getWindowId(), attempt.getCount()) : attempt;
    }

    private static Attempt storedAttempt(Object value) {
        if (value instanceof Attempt) {
            return (Attempt) value;
        }
        if (value instanceof TypedCounter) {
            TypedCounter counter = (TypedCounter) value;
            if (counter.get() > 0) {
                return new Attempt(counter.windowId, counter.get());
            }
        }
        return null;
    }

    private static boolean isMigratedZero(Object value) {
        return value == MigratedCounter.ZERO || (value instanceof TypedCounter && ((TypedCounter) value).get() == 0);
    }

    private static Attempt firstAttempt() {
        return new Attempt(UUID.randomUUID().toString(), 1);
    }

    private K key(Object principal) {
        return Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
    }

    private enum MigratedCounter {
        ZERO
    }

    private static final class TypedCounter extends AtomicInteger {

        private static final long serialVersionUID = 1L;

        private final String windowId;

        private TypedCounter(String windowId, int count) {
            super(count);
            this.windowId = windowId;
        }

        // Conditional operations compare the value with a deserialized copy in off-heap regions.
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TypedCounter)) {
                return false;
            }
            TypedCounter counter = (TypedCounter) other;
            return get() == counter.get() && Objects.equals(windowId, counter.windowId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(windowId, get());
        }
    }

    private static final class FixedWindowExpiry<V> implements ExpiryPolicy<Object, V> {

        private final Duration window;

        private FixedWindowExpiry(Duration window) {
            this.window = window;
        }

        @Override
        public Duration getExpiryForCreation(Object key, V value) {
            return window;
        }

        @Override
        public Duration getExpiryForAccess(Object key, Supplier<? extends V> value) {
            return null;
        }

        @Override
        public Duration getExpiryForUpdate(Object key, Supplier<? extends V> previous, V next) {
            return null;
        }
    }
}
