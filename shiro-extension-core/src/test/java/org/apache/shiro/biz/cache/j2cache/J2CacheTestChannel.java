package org.apache.shiro.biz.cache.j2cache;

import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.CacheProviderHolder;
import net.oschina.j2cache.J2CacheConfig;

/** Starts and closes both providers for an isolated J2Cache test channel. */
final class J2CacheTestChannel extends CacheChannel {

    private final CacheProviderHolder providers;

    static CacheChannel open(J2CacheConfig config) {
        return new J2CacheTestChannel(config, CacheProviderHolder.init(config, (region, key) -> { }));
    }

    private J2CacheTestChannel(J2CacheConfig config, CacheProviderHolder providers) {
        super(config, providers);
        this.providers = providers;
    }

    @Override
    protected void sendClearCmd(String region) {
    }

    @Override
    protected void sendEvictCmd(String region, String... keys) {
    }

    @Override
    public void close() {
        super.close();
        providers.shutdown();
    }
}
