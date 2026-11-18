package org.apache.shiro.biz.cache.redis;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;

/**
 * Credential retry state held in a Redis hash. Each principal uses one key, so
 * both operations are atomic on a single Redis Cluster slot.
 */
public final class RedisCredentialRetryStore implements CredentialRetryStore {

    private static final String RESERVE = String.join("\n",
            "local key = KEYS[1]",
            "local window = redis.call('HGET', key, 'window')",
            "local idle = ARGV[3]",
            "if window then",
            "  idle = redis.call('HGET', key, 'idleMillis')",
            "end",
            "local idleDeadline",
            "local ttlDeadline = '0'",
            "if idle and idle ~= '0' then",
            "  redis.replicate_commands()",
            "  local time = redis.call('TIME')",
            "  local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)",
            "  idleDeadline = now + tonumber(idle)",
            "  if window then",
            "    ttlDeadline = redis.call('HGET', key, 'ttlDeadline')",
            "  elseif ARGV[2] ~= '0' then",
            "    ttlDeadline = string.format('%.0f', now + tonumber(ARGV[2]))",
            "  end",
            "  if ttlDeadline ~= '0' then",
            "    idleDeadline = math.min(idleDeadline, tonumber(ttlDeadline))",
            "  end",
            "end",
            "local count",
            "if not window then",
            "  window = ARGV[1]",
            "  count = 1",
            "  redis.call('HSET', key, 'window', window, 'count', count)",
            "  if idleDeadline then",
            "    redis.call('HSET', key, 'idleMillis', idle, 'ttlDeadline', ttlDeadline)",
            "  elseif ARGV[2] ~= '0' then",
            "    redis.call('PEXPIRE', key, ARGV[2])",
            "  end",
            "else",
            "  count = redis.call('HGET', key, 'count')",
            "  if tonumber(count) < 2147483647 then",
            "    count = redis.call('HINCRBY', key, 'count', 1)",
            "  end",
            "end",
            "if idleDeadline then",
            "  redis.call('PEXPIREAT', key, string.format('%.0f', idleDeadline))",
            "end",
            "return window .. ':' .. count",
            "");
    private static final String RESERVE_DYNAMIC = String.join("\n",
            "local key = KEYS[1]",
            "local window = redis.call('HGET', key, 'window')",
            "if not window then",
            "  if ARGV[2] == '' then",
            "    return ''",
            "  end",
            "  redis.call('HSET', key, 'window', ARGV[1], 'count', 1)",
            "  if ARGV[2] ~= '0' then",
            "    redis.call('PEXPIRE', key, ARGV[2])",
            "  end",
            "  return ARGV[1] .. ':1'",
            "end",
            "local count = redis.call('HGET', key, 'count')",
            "if tonumber(count) < 2147483647 then",
            "  count = redis.call('HINCRBY', key, 'count', 1)",
            "end",
            "if ARGV[3] ~= '0' then",
            "  redis.call('PEXPIRE', key, ARGV[3])",
            "end",
            "return window .. ':' .. count",
            "");
    private static final String CLEAR = String.join("\n",
            "if redis.call('HGET', KEYS[1], 'window') == ARGV[1] then",
            "  redis.call('DEL', KEYS[1])",
            "  return '1'",
            "end",
            "return '0'",
            "");

    private static final String IMPORT_LEGACY = String.join("\n",
            "local key = KEYS[1]",
            "if redis.call('EXISTS', key) == 1 then",
            "  return '0'",
            "end",
            "if ARGV[3] ~= '0' then",
            "  redis.replicate_commands()",
            "  local time = redis.call('TIME')",
            "  local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)",
            "  if tonumber(ARGV[3]) <= now then",
            "    return '0'",
            "  end",
            "end",
            "redis.call('HSET', key, 'window', ARGV[1], 'count', ARGV[2])",
            "if ARGV[3] ~= '0' then",
            "  redis.call('PEXPIREAT', key, ARGV[3])",
            "end",
            "return '1'");

    private final ScriptExecutor executor;
    private final String namespace;
    private final Function<Object, String> principalKey;
    private final Reservation reservation;

    /**
     * @param executor Redis client operation for scripts returning a UTF-8 bulk string
     * @param namespace dedicated retry hash namespace, shared by all instances and separate from legacy keys
     * @param principalKey stable, unambiguous principal encoding, identical on every instance
     * @param expiry existing provider expiry for new windows, or zero for persistent windows
     */
    public RedisCredentialRetryStore(ScriptExecutor executor, String namespace,
                                    Function<Object, String> principalKey, Duration expiry) {
        this(executor, namespace, principalKey, expiry, Duration.ZERO);
    }

    /**
     * @param executor Redis client operation for scripts returning a UTF-8 bulk string
     * @param namespace dedicated retry hash namespace shared by all instances
     * @param principalKey stable principal encoding shared by all instances
     * @param expiry maximum lifetime of a new window, or zero for no lifetime limit
     * @param maxIdle idle period refreshed by a reservation, or zero for no idle limit
     */
    public RedisCredentialRetryStore(ScriptExecutor executor, String namespace,
                                    Function<Object, String> principalKey, Duration expiry, Duration maxIdle) {
        this(executor, namespace, principalKey, fixedReservation(expiry, maxIdle));
    }

    /**
     * Uses the provider's creation and access expiry functions with an atomic Redis reservation.
     *
     * @param executor Redis client operation for scripts returning a UTF-8 bulk string
     * @param namespace dedicated retry hash namespace shared by all instances
     * @param principalKey stable principal encoding shared by all instances
     * @param creationExpiry evaluated when a new retry window is needed. Zero creates a persistent window.
     * @param accessExpiry evaluated for each reservation. Zero preserves the current expiry.
     * @return a store using the supplied expiry functions
     */
    public static RedisCredentialRetryStore withDynamicExpiry(
            ScriptExecutor executor, String namespace, Function<Object, String> principalKey,
            Function<Object, Duration> creationExpiry, Function<Object, Duration> accessExpiry) {
        Objects.requireNonNull(creationExpiry, "creationExpiry");
        Objects.requireNonNull(accessExpiry, "accessExpiry");
        return new RedisCredentialRetryStore(executor, namespace, principalKey, (scripts, keys, principal) -> {
            String window = UUID.randomUUID().toString();
            String accessMillis = dynamicMillis(accessExpiry.apply(principal));
            String result = scripts.eval(RESERVE_DYNAMIC, keys, Arrays.asList(window, "", accessMillis));
            if (result.isEmpty()) {
                String creationMillis = dynamicMillis(creationExpiry.apply(principal));
                return scripts.eval(RESERVE_DYNAMIC, keys, Arrays.asList(window, creationMillis, accessMillis));
            }
            return result;
        });
    }

    private RedisCredentialRetryStore(ScriptExecutor executor, String namespace,
                                      Function<Object, String> principalKey, Reservation reservation) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.principalKey = Objects.requireNonNull(principalKey, "principalKey");
        this.reservation = reservation;
        if (StringUtils.isBlank(namespace)) {
            throw new IllegalArgumentException("Retry state requires a namespace");
        }
    }

    private static Reservation fixedReservation(Duration expiry, Duration maxIdle) {
        long ttl = toMillis(expiry, "expiry");
        long idle = toMillis(maxIdle, "maxIdle");
        // Allow room for the current epoch time while keeping Lua arithmetic exact.
        if (idle > 0 && (ttl > (1L << 52) || idle > (1L << 52))) {
            throw new IllegalArgumentException("Retry duration is too large for an idle expiry policy");
        }
        String expiryMillis = Long.toString(ttl);
        String idleMillis = Long.toString(idle);
        return (scripts, keys, principal) -> scripts.eval(RESERVE, keys,
                Arrays.asList(UUID.randomUUID().toString(), expiryMillis, idleMillis));
    }

    private static String dynamicMillis(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        return Long.toString(duration.isNegative() ? 0 : duration.toMillis());
    }

    private static long toMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        long millis = duration.toMillis();
        if (duration.isNegative() || (millis == 0 && !duration.isZero())) {
            throw new IllegalArgumentException(name + " must be zero or a positive millisecond duration");
        }
        return millis;
    }

    @Override
    public Attempt reserve(Object principal) {
        String result = reservation.reserve(executor, Collections.singletonList(key(principal)), principal);
        int separator = result.lastIndexOf(':');
        if (separator < 1) {
            throw new IllegalStateException("Unexpected Redis retry reservation response");
        }
        return new Attempt(result.substring(0, separator), Integer.parseInt(result.substring(separator + 1)));
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        executor.eval(CLEAR, Collections.singletonList(key(principal)), Collections.singletonList(windowId));
    }

    /**
     * Imports a fixed-expiry counter into an empty destination key during an exclusive cutover.
     * Run the import while legacy and destination clients are stopped.
     *
     * @param principal principal to migrate
     * @param count persisted count before the next reservation
     * @param deadlineMillis absolute Redis expiry in epoch milliseconds, or zero for a persistent key
     * @return true when an unexpired counter was imported
     */
    public boolean importFixedWindow(Object principal, int count, long deadlineMillis) {
        if (count < 0 || deadlineMillis < 0) {
            throw new IllegalArgumentException("Legacy count and deadline must be nonnegative");
        }
        return "1".equals(executor.eval(IMPORT_LEGACY, Collections.singletonList(key(principal)),
                Arrays.asList(UUID.randomUUID().toString(), Integer.toString(count), Long.toString(deadlineMillis))));
    }

    private String key(Object principal) {
        String encoded = Objects.requireNonNull(principalKey.apply(principal), "encoded principal");
        return namespace + ":" + encoded;
    }

    @FunctionalInterface
    private interface Reservation {

        String reserve(ScriptExecutor executor, List<String> keys, Object principal);
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
