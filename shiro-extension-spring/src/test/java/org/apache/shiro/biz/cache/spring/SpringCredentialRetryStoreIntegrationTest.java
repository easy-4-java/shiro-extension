package org.apache.shiro.biz.cache.spring;

import com.hazelcast.cache.impl.HazelcastServerCachingProvider;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.cache.Cache;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.EternalExpiryPolicy;
import org.apache.shiro.authc.ExcessiveAttemptsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.apache.shiro.biz.authc.credential.DefaultCredentialsMatcher;
import org.apache.shiro.biz.authc.credential.spring.CredentialRetryStoreAutoConfiguration;
import org.apache.shiro.cache.CacheException;
import org.junit.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.jcache.JCacheCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class SpringCredentialRetryStoreIntegrationTest {

    private static final String REGION = "credential-retry";
    private static final StringRedisSerializer UTF_8 = StringRedisSerializer.UTF_8;

    @Test
    public void springRedisManagersShareAnAtomicRetryWindowWithTheirConfiguredTtl() throws Exception {
        String port = System.getProperty("shiro.redis.test.port");
        assumeTrue("Supply an isolated Redis port", Objects.nonNull(port));
        String namespace = "test:spring-retry:" + UUID.randomUUID();
        LettuceConnectionFactory first = connectionFactory(port);
        LettuceConnectionFactory second = connectionFactory(port);
        try {
            CredentialRetryStore a = redisStore(first, namespace, false);
            CredentialRetryStore b = redisStore(second, namespace, true);
            Attempt initial = a.reserve("principal");
            long deadline = expiry(first, namespace);
            assertTrue(deadline > System.currentTimeMillis());
            assertTrue(deadline <= System.currentTimeMillis() + 15_000);

            ExecutorService workers = Executors.newFixedThreadPool(6);
            try {
                List<Callable<Attempt>> requests = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    CredentialRetryStore selected = i % 2 == 0 ? a : b;
                    requests.add(() -> selected.reserve("principal"));
                }
                Set<Integer> counts = new HashSet<>();
                for (Future<Attempt> result : workers.invokeAll(requests, 10, TimeUnit.SECONDS)) {
                    Attempt attempt = result.get();
                    assertEquals(initial.getWindowId(), attempt.getWindowId());
                    assertTrue(counts.add(attempt.getCount()));
                }
                assertEquals(24, counts.size());
                assertTrue(counts.contains(2));
                assertTrue(counts.contains(25));
            } finally {
                workers.shutdownNow();
            }
            assertEquals(deadline, expiry(second, namespace));
            b.clearIfCurrent("principal", initial.getWindowId());
            Attempt renewed = a.reserve("principal");
            assertEquals(1, renewed.getCount());
            assertNotEquals(initial.getWindowId(), renewed.getWindowId());
            b.clearIfCurrent("principal", initial.getWindowId());
            assertEquals(2, a.reserve("principal").getCount());
        } finally {
            second.destroy();
            first.destroy();
        }
    }

    @Test
    public void springRedisEnforcesTheRetryLimitAcrossMatcherInstances() {
        String port = System.getProperty("shiro.redis.test.port");
        assumeTrue("Supply an isolated Redis port", Objects.nonNull(port));
        String namespace = "test:spring-matcher:" + UUID.randomUUID();
        LettuceConnectionFactory first = connectionFactory(port);
        LettuceConnectionFactory second = connectionFactory(port);
        try (AnnotationConfigApplicationContext firstContext = matcherContext(first, namespace, false);
             AnnotationConfigApplicationContext secondContext = matcherContext(second, namespace, true)) {
            DefaultCredentialsMatcher a = firstContext.getBean(DefaultCredentialsMatcher.class);
            DefaultCredentialsMatcher b = secondContext.getBean(DefaultCredentialsMatcher.class);

            UsernamePasswordToken rejected = new UsernamePasswordToken("principal", "wrong");
            SimpleAuthenticationInfo account = new SimpleAuthenticationInfo("principal", "correct", "realm");
            assertFalse(a.doCredentialsMatch(rejected, account));
            assertFalse(b.doCredentialsMatch(rejected, account));
            assertThrows(ExcessiveAttemptsException.class, () -> a.doCredentialsMatch(rejected, account));
        } finally {
            second.destroy();
            first.destroy();
        }
    }

    @Test
    public void unwrapsSpringJCacheThroughTheShiroCacheManager() {
        Config config = new Config().setClusterName("shiro-spring-jcache-" + UUID.randomUUID());
        config.setProperty("hazelcast.logging.type", "none");
        config.setProperty("hazelcast.socket.bind.any", "false");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.thread.count", "2");
        config.setProperty("hazelcast.event.thread.count", "2");
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        HazelcastInstance member = Hazelcast.newHazelcastInstance(config);
        try (HazelcastServerCachingProvider provider = new HazelcastServerCachingProvider(member);
             javax.cache.CacheManager nativeManager = provider.getCacheManager()) {
            Cache<Object, Object> region = nativeManager.createCache(REGION, new MutableConfiguration<Object, Object>()
                    .setTypes(Object.class, Object.class)
                    .setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf()));
            region.put("principal", new AtomicInteger(3));
            JCacheCacheManager spring = new JCacheCacheManager(nativeManager);
            spring.afterPropertiesSet();
            SpringCacheManager shiro = new SpringCacheManager(spring);
            CredentialRetryStore a = shiro.getCredentialRetryStore(REGION, SpringJCacheCredentialRetryStores::fromCache);
            CredentialRetryStore b = shiro.getCredentialRetryStore(REGION, SpringJCacheCredentialRetryStores::fromCache);
            Attempt initial = a.reserve("principal");
            assertEquals(4, initial.getCount());
            assertEquals(initial.getWindowId(), b.reserve("principal").getWindowId());
            assertEquals(5, ((Attempt) region.get("principal")).getCount());
            b.clearIfCurrent("principal", initial.getWindowId());
            assertEquals(1, a.reserve("principal").getCount());
        } finally {
            member.getLifecycleService().terminate();
        }
    }

    @Test
    public void reportsMissingRegionsAndRejectsCachesWithoutTheExpectedProvider() {
        SimpleCacheManager spring = new SimpleCacheManager();
        spring.setCaches(Collections.singletonList(new ConcurrentMapCache(REGION)));
        spring.afterPropertiesSet();
        SpringCacheManager shiro = new SpringCacheManager(spring);
        assertThrows(CacheException.class,
                () -> shiro.getCredentialRetryStore("missing", SpringJCacheCredentialRetryStores::fromCache));
        assertThrows(IllegalArgumentException.class,
                () -> shiro.getCredentialRetryStore(REGION, SpringJCacheCredentialRetryStores::fromCache));
    }

    private static AnnotationConfigApplicationContext matcherContext(
            LettuceConnectionFactory factory, String namespace, boolean transactionAware) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(CredentialRetryStoreAutoConfiguration.class);
        context.registerBean(CredentialRetryStoreAutoConfiguration.STORE_BEAN_PREFIX + REGION,
                CredentialRetryStore.class, () -> redisStore(factory, namespace, transactionAware));
        context.registerBean(DefaultCredentialsMatcher.class, () -> {
            DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher();
            matcher.setRetryLimit(3);
            matcher.setCredentialsRetryCacheName(REGION);
            return matcher;
        });
        context.refresh();
        return context;
    }

    private static LettuceConnectionFactory connectionFactory(String port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        factory.afterPropertiesSet();
        return factory;
    }

    private static CredentialRetryStore redisStore(LettuceConnectionFactory factory, String namespace,
                                                   boolean transactionAware) {
        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(factory)
                .withCacheConfiguration(REGION, RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofSeconds(15)));
        if (transactionAware) {
            builder.transactionAware();
        }
        RedisCacheManager spring = builder.build();
        spring.afterPropertiesSet();
        return new SpringCacheManager(spring).getCredentialRetryStore(REGION,
                cache -> SpringDataRedisCredentialRetryStores.fromCache(cache, factory, namespace, Object::toString));
    }

    private static long expiry(LettuceConnectionFactory factory, String namespace) {
        try (RedisConnection connection = factory.getConnection()) {
            return connection.scriptingCommands().eval(
                    UTF_8.serialize("return redis.call('PEXPIRETIME', KEYS[1])"),
                    ReturnType.INTEGER, 1, UTF_8.serialize(namespace + ":principal"));
        }
    }
}
