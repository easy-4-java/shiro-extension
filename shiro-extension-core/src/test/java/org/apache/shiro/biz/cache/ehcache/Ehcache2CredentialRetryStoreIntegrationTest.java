package org.apache.shiro.biz.cache.ehcache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore.Attempt;
import org.junit.After;
import org.junit.Test;

public class Ehcache2CredentialRetryStoreIntegrationTest {

    private final CacheManager manager = CacheManager.newInstance(new Configuration());

    @After
    public void closeCache() {
        manager.shutdown();
    }

    @Test
    public void storesShareAtomicCountsThroughCopyOnReadCache() throws Exception {
        Cache cache = cache(0, 0);
        Ehcache2CredentialRetryStore<String> first = new Ehcache2CredentialRetryStore<>(cache, Object::toString);
        Ehcache2CredentialRetryStore<String> second = new Ehcache2CredentialRetryStore<>(cache, Object::toString);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt>> pending = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                Ehcache2CredentialRetryStore<String> store = i % 2 == 0 ? first : second;
                pending.add(workers.submit(() -> {
                    start.await();
                    return store.reserve("principal");
                }));
            }
            start.countDown();
            String windowId = pending.get(0).get(10, TimeUnit.SECONDS).getWindowId();
            Set<Integer> counts = new HashSet<>();
            for (Future<Attempt> future : pending) {
                Attempt attempt = future.get(10, TimeUnit.SECONDS);
                assertEquals(windowId, attempt.getWindowId());
                counts.add(attempt.getCount());
            }
            assertEquals(64, counts.size());
            assertTrue(counts.contains(1));
            assertTrue(counts.contains(64));

            first.clearIfCurrent("principal", windowId);
            Attempt next = second.reserve("principal");
            assertEquals(1, next.getCount());
            assertNotEquals(windowId, next.getWindowId());
            first.clearIfCurrent("principal", windowId);
            assertEquals(2, second.reserve("principal").getCount());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void incrementsPreserveCreationDeadlineAndRefreshConfiguredIdlePeriod() throws Exception {
        Cache cache = cache(30, 10);
        Ehcache2CredentialRetryStore<String> store = new Ehcache2CredentialRetryStore<>(cache, Object::toString);

        long created = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(5);
        Element existing = new Element("principal", new Attempt("first", 1), 1, created, created, 0,
                false, 30, 10, created);
        cache.put(existing);
        Element before = cache.getQuiet("principal");
        Thread.sleep(20);
        assertEquals(2, store.reserve("principal").getCount());
        Element after = cache.getQuiet("principal");
        assertEquals(created, after.getCreationTime());
        assertEquals(30, after.getTimeToLive());
        assertEquals(10, after.getTimeToIdle());
        assertTrue(after.getLastAccessTime() > before.getLastAccessTime());

        long closeToDeadline = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(29);
        cache.put(new Element("ending", new Attempt("ending", 1), 1, closeToDeadline, closeToDeadline, 0,
                false, 30, 10, closeToDeadline));
        assertEquals(2, store.reserve("ending").getCount());
        assertEquals(closeToDeadline + TimeUnit.SECONDS.toMillis(30), cache.getQuiet("ending").getExpirationTime());
    }

    @Test
    public void adoptsLegacyCounterWithoutRestartingItsWindow() {
        Cache cache = cache(30, 120);
        Ehcache2CredentialRetryStore<String> first = new Ehcache2CredentialRetryStore<>(cache, Object::toString);
        Ehcache2CredentialRetryStore<String> second = new Ehcache2CredentialRetryStore<>(cache, Object::toString);
        long createdAt = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(5);
        cache.put(new Element("principal", new AtomicInteger(3), 1, createdAt, createdAt, 0,
                false, 30, 120, createdAt));
        long expiry = cache.getQuiet("principal").getExpirationTime();

        Attempt adopted = first.reserve("principal");
        assertEquals(4, adopted.getCount());
        Attempt next = second.reserve("principal");
        assertEquals(5, next.getCount());
        assertEquals(adopted.getWindowId(), next.getWindowId());
        Element updated = cache.getQuiet("principal");
        assertEquals(createdAt, updated.getCreationTime());
        assertEquals(expiry, updated.getExpirationTime());
        assertEquals(120, updated.getTimeToIdle());

        cache.put(new Element("other-principal", new AtomicInteger(2)));
        long otherExpiry = cache.getQuiet("other-principal").getExpirationTime();
        first.clearIfCurrent("other-principal", "stale");
        assertEquals(2, ((AtomicInteger) cache.getQuiet("other-principal").getObjectValue()).get());
        assertEquals(otherExpiry, cache.getQuiet("other-principal").getExpirationTime());
    }

    @Test
    public void idleOnlyWindowExpiresAndStaleResetDoesNotRefreshIt() throws Exception {
        Cache cache = cache(0, 1);
        Ehcache2CredentialRetryStore<String> store = new Ehcache2CredentialRetryStore<>(cache, Object::toString);
        Attempt first = store.reserve("principal");
        long expiry = cache.getQuiet("principal").getExpirationTime();
        store.clearIfCurrent("principal", "stale");
        assertEquals(expiry, cache.getQuiet("principal").getExpirationTime());

        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.currentTimeMillis() <= expiry && System.nanoTime() < timeout) {
            Thread.sleep(10);
        }
        assertNull(cache.getQuiet("principal"));
        Attempt next = store.reserve("principal");
        assertEquals(1, next.getCount());
        assertNotEquals(first.getWindowId(), next.getWindowId());
    }

    private Cache cache(int ttl, int tti) {
        Cache cache = new Cache(new CacheConfiguration("retry-state", 100)
                .timeToLiveSeconds(ttl)
                .timeToIdleSeconds(tti)
                .copyOnRead(true)
                .copyOnWrite(true));
        manager.addCache(cache);
        return cache;
    }
}
