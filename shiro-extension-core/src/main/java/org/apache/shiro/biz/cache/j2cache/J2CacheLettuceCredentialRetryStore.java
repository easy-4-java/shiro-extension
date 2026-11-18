package org.apache.shiro.biz.cache.j2cache;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    private final J2CacheRedisLegacyWindows legacy;

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
        this(channel, config, region, namespace, principalKey, commands, null);
    }

    /**
     * @param channel channel using J2Cache's Lettuce L2 provider
     * @param config the configuration used to create the channel
     * @param region existing retry region whose expiry policy is reused
     * @param namespace dedicated retry namespace shared by all instances
     * @param principalKey stable principal encoding shared by all instances
     * @param commands caller-managed UTF-8 scripting connection for the retry store
     * @param legacyCommands binary scripting connection to the same Redis deployment for legacy keys
     */
    public J2CacheLettuceCredentialRetryStore(CacheChannel channel, J2CacheConfig config, String region,
                                             String namespace, Function<Object, String> principalKey,
                                             RedisScriptingCommands<String, String> commands,
                                             RedisScriptingCommands<byte[], byte[]> legacyCommands) {
        if (!(channel.getL2Provider() instanceof LettuceCacheProvider)) {
            throw new IllegalArgumentException("J2Cache retry state requires a Lettuce L2 provider");
        }
        Objects.requireNonNull(commands, "commands");
        RedisCredentialRetryStore.ScriptExecutor scripts = (script, keys, args) -> commands.eval(
                script, ScriptOutputType.VALUE, keys.toArray(String[]::new), args.toArray(String[]::new));
        this.delegate = new RedisCredentialRetryStore(scripts,
                namespace, principalKey, J2CacheRetryExpiry.resolve(channel, config, region, "hash"));
        this.legacy = new J2CacheRedisLegacyWindows(channel, config, region, false,
                key -> readDeadline(commands, legacyCommands, key));
    }

    private static long readDeadline(RedisScriptingCommands<String, String> commands,
                                     RedisScriptingCommands<byte[], byte[]> legacyCommands, byte[] key) {
        if (Objects.nonNull(legacyCommands)) {
            return legacyCommands.eval(J2CacheRedisLegacyWindows.DEADLINE_SCRIPT, ScriptOutputType.INTEGER, key);
        }
        String text = new String(key, StandardCharsets.UTF_8);
        if (!Arrays.equals(key, text.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("A binary Lettuce connection is required for the legacy Redis key");
        }
        return commands.eval(J2CacheRedisLegacyWindows.DEADLINE_SCRIPT, ScriptOutputType.INTEGER, text);
    }

    @Override
    public Attempt reserve(Object principal) {
        return delegate.reserve(principal);
    }

    @Override
    public void clearIfCurrent(Object principal, String windowId) {
        delegate.clearIfCurrent(principal, windowId);
    }

    /**
     * Copies a persisted L2 counter into the dedicated retry namespace during an exclusive cutover.
     *
     * @param principal original string principal key in the J2Cache retry region
     * @return true when a live legacy window was imported into an empty destination
     */
    public boolean migrateLegacyWindow(String principal) {
        return legacy.migrate(principal, delegate);
    }
}
