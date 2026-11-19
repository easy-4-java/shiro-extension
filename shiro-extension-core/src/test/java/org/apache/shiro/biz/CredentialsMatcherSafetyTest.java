/*
 * Copyright (c) 2018 (https://github.com/loong10k).
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
package org.apache.shiro.biz;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.authc.*;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.biz.authc.credential.*;
import org.apache.shiro.cache.*;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.junit.Test;
import static org.junit.Assert.*;

public class CredentialsMatcherSafetyTest {
    @Test public void successResetsOnlyItsPrincipalAndFailuresDoNotReset() {
        for (boolean hashed : new boolean[]{false, true}) {
            MapCache<Object, AtomicInteger> cache = cache();
            CredentialsMatcher matcher = matcher(hashed, cache);
            assertFalse(matcher.doCredentialsMatch(token("alice", "wrong"), account(hashed)));
            assertFalse(matcher.doCredentialsMatch(token("bob", "wrong"), account(hashed)));
            assertTrue(matcher.doCredentialsMatch(token("alice", "secret"), account(hashed)));
            assertNull(cache.get("alice"));
            assertEquals(1, cache.get("bob").get());
            assertFalse(matcher.doCredentialsMatch(token("alice", "wrong"), account(hashed)));
            assertEquals(1, cache.get("alice").get());
        }
    }
    @Test public void protectedComparisonIsCalledOnceAndFirstRejectionIsFinal() {
        AtomicInteger calls = new AtomicInteger();
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher() {
            @Override protected boolean credentialsMatch(AuthenticationToken token, AuthenticationInfo info) { return calls.incrementAndGet() > 1; }
        };
        MapCache<Object, AtomicInteger> cache = cache();
        matcher.setCacheManager(manager(cache));
        assertFalse(matcher.doCredentialsMatch(token("alice", "secret"), account(false)));
        assertEquals(1, calls.get());
        assertEquals(1, cache.get("alice").get());
    }
    @Test public void comparisonExceptionPreservesAttemptAndPropagatesUnchanged() {
        IllegalStateException failure = new IllegalStateException("local verifier failure");
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher() {
            @Override protected boolean credentialsMatch(AuthenticationToken token, AuthenticationInfo info) { throw failure; }
        };
        MapCache<Object, AtomicInteger> cache = cache();
        matcher.setCacheManager(manager(cache));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> matcher.doCredentialsMatch(token("alice", "secret"), account(false))));
        assertEquals(1, cache.get("alice").get());
    }
    @Test public void successfulProtectedComparisonRunsOnceAndClearsCounter() {
        AtomicInteger calls = new AtomicInteger();
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher() {
            @Override protected boolean credentialsMatch(AuthenticationToken token, AuthenticationInfo info) { calls.incrementAndGet(); return true; }
        };
        MapCache<Object, AtomicInteger> cache = cache();
        matcher.setCacheManager(manager(cache));
        assertTrue(matcher.doCredentialsMatch(token("alice", "secret"), account(false)));
        assertEquals(1, calls.get());
        assertNull(cache.get("alice"));
    }
    @Test public void lazyCacheIsCreatedOnceAndSafelyPublishedAcrossPrincipals() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MapCache<Object, AtomicInteger> cache = cache();
        CredentialsRetryLimitCredentialsMatcher matcher = new CredentialsRetryLimitCredentialsMatcher() {
            @Override protected Cache<Object, AtomicInteger> createCredentialsRetryCache() {
                if (creations.incrementAndGet() == 1) {
                    creating.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                }
                return cache;
            }
        };
        matcher.setHashAlgorithmName("SHA-256");
        FutureTask<Boolean> a = new FutureTask<>(() -> matcher.doCredentialsMatch(token("alice", "wrong"), account(true)));
        FutureTask<Boolean> b = new FutureTask<>(() -> matcher.doCredentialsMatch(token("bob", "wrong"), account(true)));
        Thread first = new Thread(a, "lazy-first");
        Thread second = new Thread(b, "lazy-second");
        first.start();
        try {
            assertTrue(creating.await(5, TimeUnit.SECONDS));
            second.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (second.isAlive() && second.getState() != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield();
            assertTrue(!second.isAlive() || second.getState() == Thread.State.BLOCKED);
        } finally { release.countDown(); }
        assertFalse(a.get(5, TimeUnit.SECONDS));
        assertFalse(b.get(5, TimeUnit.SECONDS));
        assertEquals(1, creations.get());
        assertEquals(1, cache.get("alice").get());
        assertEquals(1, cache.get("bob").get());
    }
    @Test
    public void configuredStoresShareLimitsAcrossMatcherInstancesAndResetOnSuccess() {
        for (boolean hashed : new boolean[]{false, true}) {
            TestRetryStore store = new TestRetryStore();
            CredentialsMatcher first = matcher(hashed, store);
            CredentialsMatcher second = matcher(hashed, store);

            assertFalse(first.doCredentialsMatch(token("alice", "wrong"), account(hashed)));
            assertFalse(second.doCredentialsMatch(token("alice", "wrong"), account(hashed)));
            assertThrows(ExcessiveAttemptsException.class,
                    () -> second.doCredentialsMatch(token("alice", "secret"), account(hashed)));
            assertTrue(first.doCredentialsMatch(token("bob", "secret"), account(hashed)));
            assertEquals(3, store.attempts.get("alice").getCount());
            assertNull(store.attempts.get("bob"));
        }
    }

    @Test
    public void lateSuccessfulComparisonCannotClearARenewedWindow() {
        TestRetryStore store = new TestRetryStore();
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher() {
            @Override
            protected boolean credentialsMatch(AuthenticationToken token, AuthenticationInfo info) {
                store.attempts.put(token.getPrincipal(), new CredentialRetryStore.Attempt("new-window", 1));
                return true;
            }
        };
        matcher.setCredentialRetryStore(store);

        assertTrue(matcher.doCredentialsMatch(token("alice", "secret"), account(false)));
        assertEquals("new-window", store.attempts.get("alice").getWindowId());
        assertEquals(1, store.attempts.get("alice").getCount());
    }

    @Test
    public void reservationFailureStopsComparisonAndPropagates() {
        IllegalStateException failure = new IllegalStateException("retry reservation failed");
        AtomicInteger comparisons = new AtomicInteger();
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher() {
            @Override
            protected boolean credentialsMatch(AuthenticationToken token, AuthenticationInfo info) {
                comparisons.incrementAndGet();
                return true;
            }
        };
        matcher.setCredentialRetryStore(new CredentialRetryStore() {
            @Override
            public Attempt reserve(Object principal) {
                throw failure;
            }

            @Override
            public void clearIfCurrent(Object principal, String windowId) {
                fail("reservation failed before there was a window to clear");
            }
        });

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> matcher.doCredentialsMatch(token("alice", "secret"), account(false))));
        assertEquals(0, comparisons.get());
    }

    @Test
    public void failedWindowClearCannotReportSuccessfulAuthentication() {
        IllegalStateException failure = new IllegalStateException("retry reset failed");
        TestRetryStore backingStore = new TestRetryStore();
        for (boolean hashed : new boolean[]{false, true}) {
            CredentialRetryStore store = new CredentialRetryStore() {
                @Override
                public Attempt reserve(Object principal) {
                    return backingStore.reserve(principal);
                }

                @Override
                public void clearIfCurrent(Object principal, String windowId) {
                    assertEquals(backingStore.attempts.get(principal).getWindowId(), windowId);
                    throw failure;
                }
            };
            CredentialsMatcher matcher = matcher(hashed, store);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> matcher.doCredentialsMatch(token("alice", "secret"), account(hashed))));
        }
        assertEquals(2, backingStore.attempts.get("alice").getCount());
    }

    private static CredentialsMatcher matcher(boolean hashed, CredentialRetryStore store) {
        if (hashed) {
            CredentialsRetryLimitCredentialsMatcher matcher = new CredentialsRetryLimitCredentialsMatcher();
            matcher.setHashAlgorithmName("SHA-256");
            matcher.setCredentialsRetryTimesLimit(3);
            matcher.setCredentialRetryStore(store);
            return matcher;
        }
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher();
        matcher.setRetryLimit(3);
        matcher.setCredentialRetryStore(store);
        return matcher;
    }

    private static final class TestRetryStore implements CredentialRetryStore {
        private final Map<Object, Attempt> attempts = new ConcurrentHashMap<>();

        @Override
        public Attempt reserve(Object principal) {
            return attempts.compute(principal, (key, previous) -> Objects.isNull(previous)
                    ? new Attempt(UUID.randomUUID().toString(), 1)
                    : new Attempt(previous.getWindowId(), previous.getCount() + 1));
        }

        @Override
        public void clearIfCurrent(Object principal, String windowId) {
            attempts.computeIfPresent(principal, (key, current) ->
                    windowId.equals(current.getWindowId()) ? null : current);
        }
    }

    private static CredentialsMatcher matcher(boolean hashed, MapCache<Object, AtomicInteger> cache) {
        if (hashed) {
            CredentialsRetryLimitCredentialsMatcher matcher = new CredentialsRetryLimitCredentialsMatcher();
            matcher.setHashAlgorithmName("SHA-256");
            matcher.setCredentialsRetryTimes(cache);
            return matcher;
        }
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher();
        matcher.setCacheManager(manager(cache));
        return matcher;
    }
    private static UsernamePasswordToken token(String principal, String password) { return new UsernamePasswordToken(principal, password); }
    private static AuthenticationInfo account(boolean hashed) {
        return new SimpleAuthenticationInfo("alice", hashed ? new SimpleHash("SHA-256", "secret").toHex() : "secret", "realm");
    }
    private static MapCache<Object, AtomicInteger> cache() { return new MapCache<>("test", new ConcurrentHashMap<>()); }
    private static CacheManager manager(Cache<Object, AtomicInteger> cache) {
        return new CacheManager() {
            @SuppressWarnings("unchecked") public <K,V> Cache<K,V> getCache(String name) { return (Cache<K,V>) cache; }
        };
    }
}
