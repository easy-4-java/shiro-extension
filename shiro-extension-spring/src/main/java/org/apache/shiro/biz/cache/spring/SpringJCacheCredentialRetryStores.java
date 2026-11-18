package org.apache.shiro.biz.cache.spring;

import javax.cache.configuration.Configuration;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.apache.shiro.biz.cache.jcache.JCacheCredentialRetryStore;
import org.springframework.cache.Cache;

/** Creates retry stores for Spring cache regions backed by JCache. */
public final class SpringJCacheCredentialRetryStores {

    private SpringJCacheCredentialRetryStores() {
    }

    /**
     * @param cache Spring cache exposing a dedicated JCache region for retry attempts
     * @return a retry store using the provider's atomic entry processing and expiry
     */
    @SuppressWarnings("unchecked")
    public static CredentialRetryStore fromCache(Cache cache) {
        Object nativeCache = cache.getNativeCache();
        if (!(nativeCache instanceof javax.cache.Cache<?, ?>)) {
            throw new IllegalArgumentException("Spring retry cache must expose a JCache region");
        }

        Configuration<?, ?> configuration = ((javax.cache.Cache<?, ?>) nativeCache).getConfiguration(Configuration.class);
        if (!configuration.getValueType().isAssignableFrom(Attempt.class)) {
            throw new IllegalArgumentException("JCache retry region must accept credential retry attempts");
        }
        return new JCacheCredentialRetryStore((javax.cache.Cache<Object, Attempt>) nativeCache);
    }
}
