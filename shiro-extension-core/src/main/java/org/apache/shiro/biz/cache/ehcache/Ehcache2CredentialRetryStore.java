package org.apache.shiro.biz.cache.ehcache;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.TerracottaConfiguration.Consistency;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;

/**
 * Credential retry state backed by Ehcache 2 key locks. Shared deployments require
 * a strongly consistent Terracotta region.
 *
 * @param <K> serializable key used by the cache
 */
public final class Ehcache2CredentialRetryStore<K extends Serializable> implements CredentialRetryStore {

    private final Ehcache cache;
    private final Function<Object, K> principalKey;

    /**
     * @param cache dedicated retry region with the application's TTL and TTI
     * @param principalKey consistent principal key encoding for all callers sharing the cache
     */
    public Ehcache2CredentialRetryStore(Ehcache cache, Function<Object, K> principalKey) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
        CacheConfiguration configuration = cache.getCacheConfiguration();
        if (configuration.isTerracottaClustered() && configuration.getTerracottaConsistency() != Consistency.STRONG) {
            throw new IllegalArgumentException("Clustered retry cache must use strong consistency");
        }
    }

    @Override
    public Attempt reserve(Object principal) {
        K key = key(principal);
        cache.acquireWriteLockOnKey(key);
        try {
            Element entry = cache.get(key);
            if (Objects.isNull(entry)) {
                Attempt initial = new Attempt(UUID.randomUUID().toString(), 1);
                cache.put(new Element(key, initial));
                return initial;
            }

            Attempt current = (Attempt) entry.getObjectValue();
            if (current.getCount() == Integer.MAX_VALUE) {
                return current;
            }

            Attempt next = new Attempt(current.getWindowId(), current.getCount() + 1);
            // The replacement keeps the original TTL origin and counts this read as idle activity.
            cache.put(new Element(key, next, entry.getVersion(), entry.getCreationTime(),
                    entry.getLastAccessTime(), 0L, false, entry.getTimeToLive(), entry.getTimeToIdle(),
                    entry.getLastUpdateTime()));
            return next;
        } finally {
            cache.releaseWriteLockOnKey(key);
        }
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        K key = key(principal);
        Objects.requireNonNull(windowId, "windowId");
        cache.acquireWriteLockOnKey(key);
        try {
            Element entry = cache.getQuiet(key);
            if (Objects.nonNull(entry) && windowId.equals(((Attempt) entry.getObjectValue()).getWindowId())) {
                cache.remove(key);
            }
        } finally {
            cache.releaseWriteLockOnKey(key);
        }
    }

    private K key(Object principal) {
        return Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
    }
}
