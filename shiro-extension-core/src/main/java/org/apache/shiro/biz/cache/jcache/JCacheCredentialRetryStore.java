package org.apache.shiro.biz.cache.jcache;

import java.util.Objects;
import java.util.UUID;
import javax.cache.Cache;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.MutableEntry;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;

/**
 * Shared retry state in a dedicated JCache with a fixed creation expiry.
 * The cache provider executes each state change atomically against one entry.
 */
public final class JCacheCredentialRetryStore implements CredentialRetryStore {

    private final Cache<Object, Attempt> cache;

    /**
     * @param cache shared retry cache using store-by-value and a nonzero creation expiry
     *              or eternal expiry. Its expiry policy must leave the deadline unchanged
     *              on both access and update. All cluster members must have this module.
     */
    public JCacheCredentialRetryStore(Cache<Object, Attempt> cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
        CompleteConfiguration<?, ?> configuration = cache.getConfiguration(CompleteConfiguration.class);
        ExpiryPolicy policy = configuration.getExpiryPolicyFactory().create();
        Duration creation = policy.getExpiryForCreation();
        if (!configuration.isStoreByValue() || Objects.isNull(creation) || creation.isZero()
                || Objects.nonNull(policy.getExpiryForUpdate()) || Objects.nonNull(policy.getExpiryForAccess())) {
            throw new IllegalArgumentException("Retry cache must store by value and preserve its creation expiry");
        }
    }

    @Override
    public Attempt reserve(Object principal) {
        return cache.invoke(principal, Reserve.INSTANCE, UUID.randomUUID().toString());
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        cache.invoke(principal, Clear.INSTANCE, Objects.requireNonNull(windowId, "windowId"));
    }

    private enum Reserve implements EntryProcessor<Object, Attempt, Attempt> {
        INSTANCE;

        @Override
        public Attempt process(MutableEntry<Object, Attempt> entry, Object... arguments) {
            Attempt current = entry.getValue();
            Attempt next;
            if (Objects.isNull(current)) {
                next = new Attempt((String) arguments[0], 1);
            } else if (current.getCount() == Integer.MAX_VALUE) {
                return current;
            } else {
                next = new Attempt(current.getWindowId(), current.getCount() + 1);
            }
            entry.setValue(next);
            return next;
        }
    }

    private enum Clear implements EntryProcessor<Object, Attempt, Void> {
        INSTANCE;

        @Override
        public Void process(MutableEntry<Object, Attempt> entry, Object... arguments) {
            Attempt current = entry.getValue();
            if (Objects.nonNull(current) && current.getWindowId().equals(arguments[0])) {
                entry.remove();
            }
            return null;
        }
    }
}
