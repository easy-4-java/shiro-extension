package org.apache.shiro.biz.cache.j2cache;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import java.util.Objects;
import java.util.function.Function;
import net.oschina.j2cache.CacheChannel;
import net.oschina.j2cache.J2CacheConfig;
import net.oschina.j2cache.lettuce.LettuceCacheProvider;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.cache.redis.RedisCredentialRetryStore;

/** Credential retry state in an existing J2Cache region backed by Lettuce. */
public final class J2CacheLettuceCredentialRetryStore implements CredentialRetryStore {

    private final RedisCredentialRetryStore delegate;

    /**
     * @param channel channel using J2Cache's Lettuce L2 provider
     * @param config the configuration used to create the channel
     * @param region existing retry region whose expiry policy is reused
     * @param namespace dedicated retry namespace shared by all instances
     * @param principalKey stable principal encoding shared by all instances
     * @param commands caller-managed UTF-8 scripting connection to the same Redis database or cluster
     */
    public J2CacheLettuceCredentialRetryStore(CacheChannel channel, J2CacheConfig config, String region,
                                             String namespace, Function<Object, String> principalKey,
                                             RedisScriptingCommands<String, String> commands) {
        if (!(channel.getL2Provider() instanceof LettuceCacheProvider)) {
            throw new IllegalArgumentException("J2Cache retry state requires a Lettuce L2 provider");
        }
        Objects.requireNonNull(commands, "commands");
        this.delegate = new RedisCredentialRetryStore(
                (script, keys, args) -> commands.eval(script, ScriptOutputType.VALUE,
                        keys.toArray(new String[0]), args.toArray(new String[0])),
                namespace, principalKey, J2CacheRetryExpiry.resolve(channel, config, region, "hash"));
    }

    @Override
    public Attempt reserve(Object principal) {
        return delegate.reserve(principal);
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        delegate.clearIfCurrent(principal, windowId);
    }
}
