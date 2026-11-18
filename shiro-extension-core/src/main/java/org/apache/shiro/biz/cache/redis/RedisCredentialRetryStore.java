package org.apache.shiro.biz.cache.redis;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;

/**
 * Credential retry state held in a Redis hash. Each principal uses one key, so
 * both operations are atomic on a single Redis Cluster slot.
 */
public final class RedisCredentialRetryStore implements CredentialRetryStore {

    private static final String RESERVE = """
            local window = redis.call('HGET', KEYS[1], 'window')
            if not window then
              redis.call('HSET', KEYS[1], 'window', ARGV[1], 'count', 1)
              if ARGV[2] ~= '0' then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
              end
              return ARGV[1] .. ':1'
            end
            local count = redis.call('HGET', KEYS[1], 'count')
            if tonumber(count) < 2147483647 then
              count = redis.call('HINCRBY', KEYS[1], 'count', 1)
            end
            return window .. ':' .. count
            """;
    private static final String CLEAR = """
            if redis.call('HGET', KEYS[1], 'window') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return '1'
            end
            return '0'
            """;

    private final ScriptExecutor executor;
    private final String namespace;
    private final Function<Object, String> principalKey;
    private final String expiryMillis;

    /**
     * @param executor Redis client operation for scripts returning a UTF-8 bulk string
     * @param namespace dedicated retry hash namespace, shared by all instances and separate from legacy keys
     * @param principalKey stable, unambiguous principal encoding, identical on every instance
     * @param expiry existing provider expiry for new windows, or zero for persistent windows
     */
    public RedisCredentialRetryStore(ScriptExecutor executor, String namespace,
                                    Function<Object, String> principalKey, Duration expiry) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
        Objects.requireNonNull(expiry, "expiry");
        long millis = expiry.toMillis();
        if (namespace.isBlank() || expiry.isNegative() || (millis == 0 && !expiry.isZero())) {
            throw new IllegalArgumentException("Retry state requires a namespace and a nonnegative millisecond expiry");
        }
        this.expiryMillis = Long.toString(millis);
    }

    @Override
    public Attempt reserve(Object principal) {
        String result = executor.eval(RESERVE, List.of(key(principal)),
                List.of(UUID.randomUUID().toString(), expiryMillis));
        int separator = result.lastIndexOf(':');
        if (separator < 1) {
            throw new IllegalStateException("Unexpected Redis retry reservation response");
        }
        return new Attempt(result.substring(0, separator), Integer.parseInt(result.substring(separator + 1)));
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        executor.eval(CLEAR, List.of(key(principal)), List.of(windowId));
    }

    private String key(Object principal) {
        String encoded = Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
        return namespace + ":" + encoded;
    }

    /** Executes one script on the primary Redis node owning its key. */
    @FunctionalInterface
    public interface ScriptExecutor {

        /**
         * @param script Lua script to execute
         * @param keys Redis keys supplied via KEYS
         * @param args script arguments supplied via ARGV
         * @return decoded bulk string from Redis
         */
        String eval(String script, List<String> keys, List<String> args);
    }
}
