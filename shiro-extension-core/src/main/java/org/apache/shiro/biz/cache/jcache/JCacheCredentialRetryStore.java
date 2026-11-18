package org.apache.shiro.biz.cache.jcache;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.cache.Cache;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.MutableEntry;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.cache.CacheException;

/**
 * Shared retry state in a dedicated JCache with a fixed creation expiry.
 * The cache provider executes each state change atomically against one entry.
 */
public final class JCacheCredentialRetryStore implements CredentialRetryStore {

    private final Cache<Object, Object> cache;
    private final boolean acceptsLegacyCounters;
    private final boolean usesTypedCounters;

    /**
     * @param cache shared retry cache using store-by-value and a nonzero creation expiry
     *              or eternal expiry. Its expiry policy must leave the deadline unchanged
     *              on both access and update. All cluster members must have this module.
     *              To migrate legacy counters, stop legacy clients before reusing
     *              the region. An AtomicInteger-typed region must preserve the
     *              serializable counter subtype and its window ID.
     */
    @SuppressWarnings("unchecked")
    public JCacheCredentialRetryStore(Cache<Object, ?> cache) {
        CompleteConfiguration<?, ?> configuration =
                Objects.requireNonNull(cache, "cache").getConfiguration(CompleteConfiguration.class);
        Class<?> valueType = configuration.getValueType();
        this.usesTypedCounters = AtomicInteger.class.equals(valueType);
        if (!usesTypedCounters && !valueType.isAssignableFrom(Attempt.class)) {
            throw new IllegalArgumentException("JCache retry region must accept credential retry attempts");
        }
        // Provider configuration validates the values that the entry processor writes.
        this.cache = (Cache<Object, Object>) cache;
        this.acceptsLegacyCounters = valueType.isAssignableFrom(AtomicInteger.class);
        ExpiryPolicy policy = configuration.getExpiryPolicyFactory().create();
        Duration creation = policy.getExpiryForCreation();
        if (!configuration.isStoreByValue() || Objects.isNull(creation) || creation.isZero()
                || Objects.nonNull(policy.getExpiryForUpdate()) || Objects.nonNull(policy.getExpiryForAccess())) {
            throw new IllegalArgumentException("Retry cache must store by value and preserve its creation expiry");
        }
    }

    @Override
    public Attempt reserve(Object principal) {
        return cache.invoke(principal, Reserve.INSTANCE, UUID.randomUUID().toString(), usesTypedCounters);
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        cache.invoke(principal, Clear.INSTANCE, Objects.requireNonNull(windowId, "windowId"));
    }

    /**
     * Restores a live retry window to the legacy counter format in the same entry.
     * Pause old and new retry clients and drain outstanding writes before restoring.
     *
     * @param principal original principal key in the shared retry region
     * @return true when an active retry window was restored without changing its count or deadline
     * @throws IllegalStateException when the region cannot store legacy counters
     */
    public boolean restoreLegacyWindow(Object principal) {
        if (!acceptsLegacyCounters) {
            throw new IllegalStateException("JCache retry region must accept legacy counters for restore");
        }
        return cache.invoke(principal, RestoreLegacy.INSTANCE);
    }

    private static Attempt storedAttempt(Object value) {
        if (value instanceof Attempt) {
            return (Attempt) value;
        }
        if (value instanceof TypedCounter) {
            TypedCounter counter = (TypedCounter) value;
            return new Attempt(counter.windowId, counter.get());
        }
        return null;
    }

    private static final class TypedCounter extends AtomicInteger {
        private static final long serialVersionUID = 1L;
        private final String windowId;

        private TypedCounter(Attempt attempt) {
            super(attempt.getCount());
            this.windowId = attempt.getWindowId();
        }
    }

    private enum RestoreLegacy implements EntryProcessor<Object, Object, Boolean> {
        INSTANCE;

        @Override
        public Boolean process(MutableEntry<Object, Object> entry, Object... arguments) {
            // Checking existence first keeps a read-through loader from recreating an expired entry.
            if (!entry.exists()) {
                return false;
            }
            Attempt current = storedAttempt(entry.getValue());
            if (Objects.nonNull(current)) {
                entry.setValue(new AtomicInteger(current.getCount()));
                return true;
            }
            return false;
        }
    }

    private enum Reserve implements EntryProcessor<Object, Object, Attempt> {
        INSTANCE;

        @Override
        public Attempt process(MutableEntry<Object, Object> entry, Object... arguments) {
            Object value = entry.getValue();
            Attempt current = storedAttempt(value);
            Attempt next;
            if (Objects.nonNull(current)) {
                if (current.getCount() == Integer.MAX_VALUE) {
                    return current;
                }
                next = new Attempt(current.getWindowId(), current.getCount() + 1);
            } else if (Objects.isNull(value)) {
                next = new Attempt((String) arguments[0], 1);
            } else if (value instanceof AtomicInteger) {
                int count = ((AtomicInteger) value).get();
                if (count < 0) {
                    throw new CacheException("Legacy credential retry count is negative");
                }
                next = new Attempt((String) arguments[0], count == Integer.MAX_VALUE ? count : count + 1);
            } else {
                throw new CacheException("JCache retry entry contains an unsupported value");
            }
            entry.setValue(Boolean.TRUE.equals(arguments[1]) ? new TypedCounter(next) : next);
            return next;
        }
    }

    private enum Clear implements EntryProcessor<Object, Object, Void> {
        INSTANCE;

        @Override
        public Void process(MutableEntry<Object, Object> entry, Object... arguments) {
            Attempt current = storedAttempt(entry.getValue());
            if (Objects.nonNull(current) && current.getWindowId().equals(arguments[0])) {
                entry.remove();
            }
            return null;
        }
    }
}
