/*
 * Copyright (c) 2018, Loong Wan (https://github.com/loong10k).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.shiro.biz.cache.spring;

import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheException;
import org.apache.shiro.cache.CacheManager;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Spring CacheManager Wrapper
 * @author wangjie (https://github.com/wj596)
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class SpringCacheManager implements CacheManager {

	private final org.springframework.cache.CacheManager delegator;
	private final ConcurrentMap<String, SpringCache> CACHES = new ConcurrentHashMap<String, SpringCache>();
	
	public SpringCacheManager(org.springframework.cache.CacheManager cacheManager){
		this.delegator = cacheManager;
	}
	
    /**
     * Binds a retry store to the named cache through its provider-specific factory.
     *
     * @param cacheName the Spring cache region whose retry policy is used
     * @param providerFactory factory that opens an atomic retry store for the cache provider
     * @return the provider-backed retry store
     */
    public CredentialRetryStore getCredentialRetryStore(
            String cacheName, Function<? super org.springframework.cache.Cache, ? extends CredentialRetryStore> providerFactory) {
        Objects.requireNonNull(providerFactory, "providerFactory");
        org.springframework.cache.Cache springCache = delegator.getCache(cacheName);
        if (Objects.isNull(springCache)) {
            throw new CacheException("Spring retry cache is unavailable: " + cacheName);
        }
        return Objects.requireNonNull(providerFactory.apply(springCache), "providerStore");
    }

	@Override
	public <K, V> Cache<K, V> getCache(String cacheName) throws CacheException {
		SpringCache<K,V> cache = this.CACHES.get(cacheName);
		if (cache != null) {
			return cache;
		}
		else {
			synchronized (this.CACHES) {
				cache = this.CACHES.get(cacheName);
				if (cache == null) {
					org.springframework.cache.Cache springCache = this.delegator.getCache(cacheName);
					cache = new SpringCache(cacheName,springCache);
					this.CACHES.put(cacheName, cache);
				}
				return cache;
			}
		}
	}

}
