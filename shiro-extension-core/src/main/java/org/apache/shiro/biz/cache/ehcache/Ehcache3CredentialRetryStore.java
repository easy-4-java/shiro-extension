package org.apache.shiro.biz.cache.ehcache;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
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

    private final Cache<K, Attempt> cache;
    private final Function<Object, K> principalKey;

    /**
     * @param cache dedicated retry cache configured with {@link #fixedWindowExpiry(Duration)}
     *              or Ehcache's eternal expiry
     * @param principalKey consistent principal key encoding for all callers sharing the cache
     */
    public Ehcache3CredentialRetryStore(Cache<K, Attempt> cache, Function<Object, K> principalKey) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
        ExpiryPolicy<? super K, ? super Attempt> expiry = cache.getRuntimeConfiguration().getExpiryPolicy();
        if (!(expiry instanceof FixedWindowExpiry) && !ExpiryPolicy.NO_EXPIRY.equals(expiry)) {
            throw new IllegalArgumentException("Retry cache must preserve its creation expiry");
        }
    }

    /**
     * Returns an expiry policy for a dedicated retry cache.
     *
     * @param window configured duration for a newly created retry window
     * @return policy that retains the first deadline on reads and updates
     */
    public static ExpiryPolicy<Object, Attempt> fixedWindowExpiry(Duration window) {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Retry window must be positive");
        }
        return new FixedWindowExpiry(window);
    }

    @Override
    public Attempt reserve(Object principal) {
        K key = key(principal);
        for (int conflict = 0; conflict < MAX_CONFLICTS; conflict++) {
            Attempt current = cache.get(key);
            if (Objects.isNull(current)) {
                Attempt initial = new Attempt(UUID.randomUUID().toString(), 1);
                if (Objects.isNull(cache.putIfAbsent(key, initial))) {
                    return initial;
                }
            } else {
                if (current.getCount() == Integer.MAX_VALUE) {
                    return current;
                }
                Attempt next = new Attempt(current.getWindowId(), current.getCount() + 1);
                if (cache.replace(key, current, next)) {
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
            Attempt current = cache.get(key);
            if (Objects.isNull(current) || !windowId.equals(current.getWindowId()) || cache.remove(key, current)) {
                return;
            }
        }
        throw new CacheException("Unable to clear credential retry state within the conflict limit");
    }

    private K key(Object principal) {
        return Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
    }

    private static final class FixedWindowExpiry implements ExpiryPolicy<Object, Attempt> {

        private final Duration window;

        private FixedWindowExpiry(Duration window) {
            this.window = window;
        }

        @Override
        public Duration getExpiryForCreation(Object key, Attempt value) {
            return window;
        }

        @Override
        public Duration getExpiryForAccess(Object key, Supplier<? extends Attempt> value) {
            return null;
        }

        @Override
        public Duration getExpiryForUpdate(Object key, Supplier<? extends Attempt> previous, Attempt next) {
            return null;
        }
    }
}
