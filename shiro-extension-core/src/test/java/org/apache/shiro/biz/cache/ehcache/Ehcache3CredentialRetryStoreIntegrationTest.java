package org.apache.shiro.biz.cache.ehcache;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.expiry.ExpiryPolicy;
import org.ehcache.impl.internal.TimeSourceConfiguration;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class Ehcache3CredentialRetryStoreIntegrationTest {

    @Test
    public void reservesUniqueCountsWithSerializedValuesAndSaturates() throws Exception {
        try (CacheManager manager = manager(new AtomicLong(10_000), ExpiryPolicy.NO_EXPIRY)) {
            Cache<String, Attempt> cache = manager.getCache("retry", String.class, Attempt.class);
            Ehcache3CredentialRetryStore<String> first = new Ehcache3CredentialRetryStore<>(cache, Object::toString);
            Ehcache3CredentialRetryStore<String> second = new Ehcache3CredentialRetryStore<>(cache, Object::toString);
            ExecutorService threads = Executors.newFixedThreadPool(6);
            try {
                List<Callable<Attempt>> requests = new ArrayList<>();
                for (int index = 0; index < 24; index++) {
                    Ehcache3CredentialRetryStore<String> store = index % 2 == 0 ? first : second;
                    requests.add(() -> store.reserve("alice"));
                }
                Set<Integer> counts = new HashSet<>();
                Set<String> windows = new HashSet<>();
                for (Future<Attempt> future : threads.invokeAll(requests, 5, TimeUnit.SECONDS)) {
                    Attempt result = future.get();
                    assertTrue(counts.add(result.getCount()));
                    windows.add(result.getWindowId());
                }
                assertEquals(24, counts.size());
                assertEquals(1, windows.size());
                Attempt current = cache.get("alice");
                assertEquals(24, current.getCount());
                cache.put("alice", new Attempt(current.getWindowId(), Integer.MAX_VALUE));
                assertEquals(Integer.MAX_VALUE, first.reserve("alice").getCount());
            } finally {
                threads.shutdownNow();
            }
        }
    }

    @Test
    public void preservesOriginalDeadlineAndIgnoresResetFromExpiredWindow() {
        AtomicLong time = new AtomicLong(10_000);
        try (CacheManager manager = manager(time, Ehcache3CredentialRetryStore.fixedWindowExpiry(Duration.ofSeconds(3)))) {
            Cache<String, Attempt> cache = manager.getCache("retry", String.class, Attempt.class);
            Ehcache3CredentialRetryStore<String> store = new Ehcache3CredentialRetryStore<>(cache, Object::toString);
            Attempt initial = store.reserve("alice");
            time.set(12_000);
            assertEquals(2, store.reserve("alice").getCount());
            time.set(12_999);
            assertEquals(3, store.reserve("alice").getCount());
            time.set(13_001);
            assertNull(cache.get("alice"));

            Attempt renewed = store.reserve("alice");
            assertNotEquals(initial.getWindowId(), renewed.getWindowId());
            assertEquals(1, renewed.getCount());
            store.clearIfCurrent("alice", initial.getWindowId());
            assertEquals(2, store.reserve("alice").getCount());
            store.clearIfCurrent("alice", renewed.getWindowId());
            assertNull(cache.get("alice"));
        }
    }

    @Test
    public void migratesOffHeapLegacyCountersWithoutRestartingTheDeadline() {
        AtomicLong time = new AtomicLong(10_000);
        try (CacheManager manager = CacheManagerBuilder.newCacheManagerBuilder()
                .using(new TimeSourceConfiguration(time::get))
                .withCache("legacy", CacheConfigurationBuilder.newCacheConfigurationBuilder(
                                String.class, Serializable.class,
                                ResourcePoolsBuilder.newResourcePoolsBuilder().offheap(2, MemoryUnit.MB))
                        .withExpiry(Ehcache3CredentialRetryStore.fixedWindowExpiry(Duration.ofSeconds(3))))
                .build(true)) {
            Cache<String, Serializable> cache = manager.getCache("legacy", String.class, Serializable.class);
            cache.put("alice", new AtomicInteger(3));
            cache.put("zero", new AtomicInteger(0));
            Ehcache3CredentialRetryStore<String> store = new Ehcache3CredentialRetryStore<>(cache, Object::toString);
            time.set(12_000);

            assertTrue(store.migrateLegacyWindow("alice"));
            assertTrue(store.migrateLegacyWindow("zero"));
            assertFalse(store.migrateLegacyWindow("missing"));
            assertFalse(store.migrateLegacyWindow("alice"));
            store.clearIfCurrent("zero", "stale");
            assertEquals(1, store.reserve("zero").getCount());
            Attempt preserved = store.reserve("alice");
            assertEquals(4, preserved.getCount());
            time.set(13_001);
            assertNull(cache.get("alice"));
            assertNull(cache.get("zero"));
            Attempt renewed = store.reserve("alice");
            assertEquals(1, renewed.getCount());
            assertNotEquals(preserved.getWindowId(), renewed.getWindowId());
        }
    }

    @Test
    public void migratesAndReservesSerializedCountersInAnAtomicIntegerRegion() throws Exception {
        AtomicLong time = new AtomicLong(10_000);
        try (CacheManager manager = CacheManagerBuilder.newCacheManagerBuilder()
                .using(new TimeSourceConfiguration(time::get))
                .withCache("legacy-only", CacheConfigurationBuilder.newCacheConfigurationBuilder(
                                String.class, AtomicInteger.class,
                                ResourcePoolsBuilder.newResourcePoolsBuilder().offheap(2, MemoryUnit.MB))
                        .withExpiry(Ehcache3CredentialRetryStore.fixedWindowExpiry(Duration.ofSeconds(3))))
                .build(true)) {
            Cache<String, AtomicInteger> cache = manager.getCache("legacy-only", String.class, AtomicInteger.class);
            cache.put("alice", new AtomicInteger(3));
            cache.put("zero", new AtomicInteger(0));
            Ehcache3CredentialRetryStore<String> first = new Ehcache3CredentialRetryStore<>(cache, Object::toString);
            Ehcache3CredentialRetryStore<String> second = new Ehcache3CredentialRetryStore<>(cache, Object::toString);
            time.set(12_000);

            assertTrue(first.migrateLegacyWindow("alice"));
            assertFalse(second.migrateLegacyWindow("alice"));
            assertTrue(first.migrateLegacyWindow("zero"));
            first.clearIfCurrent("zero", "stale");
            assertEquals(1, second.reserve("zero").getCount());

            Attempt initial = first.reserve("alice");
            assertEquals(4, initial.getCount());
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                List<Future<Attempt>> attempts = threads.invokeAll(List.of(
                        () -> first.reserve("alice"), () -> second.reserve("alice")));
                Set<Integer> counts = new HashSet<>();
                for (Future<Attempt> attempt : attempts) {
                    Attempt result = attempt.get(5, TimeUnit.SECONDS);
                    assertEquals(initial.getWindowId(), result.getWindowId());
                    counts.add(result.getCount());
                }
                assertEquals(Set.of(5, 6), counts);
            } finally {
                threads.shutdownNow();
            }
            assertEquals(6, cache.get("alice").get());
            time.set(13_001);
            assertNull(cache.get("alice"));
            assertNull(cache.get("zero"));

            Attempt renewed = second.reserve("alice");
            assertNotEquals(initial.getWindowId(), renewed.getWindowId());
            first.clearIfCurrent("alice", initial.getWindowId());
            assertEquals(1, cache.get("alice").get());
            second.clearIfCurrent("alice", renewed.getWindowId());
            assertNull(cache.get("alice"));
        }
    }

    @Test
    public void rejectsPoliciesThatReplaceExpiryOnWrites() {
        try (CacheManager manager = manager(new AtomicLong(10_000),
                ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofSeconds(5)))) {
            Cache<String, Attempt> cache = manager.getCache("retry", String.class, Attempt.class);
            assertThrows(IllegalArgumentException.class,
                    () -> new Ehcache3CredentialRetryStore<>(cache, Object::toString));
        }
    }

    private static CacheManager manager(AtomicLong time, ExpiryPolicy<? super String, ? super Attempt> policy) {
        return CacheManagerBuilder.newCacheManagerBuilder()
                .using(new TimeSourceConfiguration(time::get))
                .withCache("retry", CacheConfigurationBuilder.newCacheConfigurationBuilder(
                                String.class, Attempt.class, ResourcePoolsBuilder.newResourcePoolsBuilder().offheap(2, MemoryUnit.MB))
                        .withExpiry(policy))
                .build(true);
    }
}
