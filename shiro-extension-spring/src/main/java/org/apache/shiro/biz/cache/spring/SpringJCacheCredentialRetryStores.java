package org.apache.shiro.biz.cache.spring;

import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
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
        if (!(cache.getNativeCache() instanceof javax.cache.Cache<?, ?> nativeCache)) {
            throw new IllegalArgumentException("Spring retry cache must expose a JCache region");
        }

        return new JCacheCredentialRetryStore((javax.cache.Cache<Object, ?>) nativeCache);
    }
}
