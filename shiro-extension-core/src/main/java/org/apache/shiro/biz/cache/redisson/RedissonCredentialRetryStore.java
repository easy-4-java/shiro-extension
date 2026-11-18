package org.apache.shiro.biz.cache.redisson;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.cache.redis.RedisCredentialRetryStore;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Shared retry state using Redisson's Redis connection and the configured TTL and idle period.
 */
public final class RedissonCredentialRetryStore implements CredentialRetryStore {

    private final RedisCredentialRetryStore delegate;

    /**
     * @param client the configured Redisson client
     * @param namespace dedicated retry namespace shared by all clients
     * @param principalKey stable principal encoding shared by all clients
     * @param ttl configured maximum lifetime, or zero for no TTL
     * @param maxIdle configured idle timeout, or zero for no idle timeout
     */
    public RedissonCredentialRetryStore(RedissonClient client, String namespace,
                                       Function<Object, String> principalKey, Duration ttl, Duration maxIdle) {
        RScript scripts = Objects.requireNonNull(client, "client").getScript(StringCodec.INSTANCE);
        this.delegate = new RedisCredentialRetryStore(
                (script, keys, args) -> scripts.eval(keys.get(0), RScript.Mode.READ_WRITE, script,
                        RScript.ReturnType.VALUE, List.<Object>copyOf(keys), args.toArray()),
                namespace, principalKey, ttl, maxIdle);
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
