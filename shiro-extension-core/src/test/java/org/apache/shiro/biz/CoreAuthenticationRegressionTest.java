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

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.authc.*;
import org.apache.shiro.biz.authc.credential.DefaultCredentialsMatcher;
import org.apache.shiro.biz.authc.pam.AtLeastTwoAuthenticatorStrategy;
import org.apache.shiro.biz.authc.pam.DefaultModularRealmAuthenticator;
import org.apache.shiro.cache.MapCache;
import org.apache.shiro.realm.Realm;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline regressions for this repository's authentication implementations. */
public class CoreAuthenticationRegressionTest {
    private final AuthenticationToken token = new UsernamePasswordToken("alice", "secret");

    @Test public void singleSupportingRealmIsCalledAndUnsupportedRealmIsNot() {
        StubRealm unsupported = new StubRealm("unsupported", false, null);
        StubRealm supported = new StubRealm("supported", true, info("supported"));
        DefaultModularRealmAuthenticator authenticator = authenticator(unsupported, supported);
        assertEquals("alice", authenticator.authenticate(token).getPrincipals().getPrimaryPrincipal());
        assertEquals(0, unsupported.calls);
        assertEquals(1, supported.calls);
    }

    @Test public void singleRealmAuthenticationPreservesCustomFailure() {
        StubRealm realm = new StubRealm("locked", true, null);
        LockedAccountException failure = new LockedAccountException("locked locally");
        realm.failure = failure;
        assertSame(failure, assertThrows(LockedAccountException.class,
                () -> authenticator(realm).authenticate(token)));
        assertEquals(1, realm.calls);
    }

    @Test public void noSupportingRealmRejectsBeforeCallingAnyRealm() {
        StubRealm first = new StubRealm("first", false, null);
        StubRealm second = new StubRealm("second", false, null);
        assertThrows(AuthenticationException.class, () -> authenticator(first, second).authenticate(token));
        assertEquals(0, first.calls);
        assertEquals(0, second.calls);
    }

    @Test public void missingRealmsAndUnknownAccountAreRejected() {
        assertThrows(AuthenticationException.class,
                () -> new DefaultModularRealmAuthenticator().authenticate(token));
        assertThrows(UnknownAccountException.class,
                () -> authenticator(new StubRealm("empty", true, null)).authenticate(token));
    }

    @Test public void twoRealmStrategyAcceptsTwoIndependentSuccesses() {
        StubRealm first = new StubRealm("first", true, info("first"));
        StubRealm second = new StubRealm("second", true, info("second"));
        DefaultModularRealmAuthenticator authenticator = authenticator(first, second);
        authenticator.setAuthenticationStrategy(new AtLeastTwoAuthenticatorStrategy());
        AuthenticationInfo result = authenticator.authenticate(token);
        assertEquals(2, result.getPrincipals().getRealmNames().size());
        assertTrue(result.getPrincipals().getRealmNames().containsAll(Arrays.asList("first", "second")));
        assertEquals(1, first.calls);
        assertEquals(1, second.calls);
    }

    @Test public void twoRealmStrategyRejectsOneSuccessAndOneFailure() {
        StubRealm first = new StubRealm("first", true, info("first"));
        StubRealm second = new StubRealm("second", true, null);
        second.failure = new IncorrectCredentialsException("wrong password");
        DefaultModularRealmAuthenticator authenticator = authenticator(first, second);
        authenticator.setAuthenticationStrategy(new AtLeastTwoAuthenticatorStrategy());
        assertThrows(AuthenticationException.class, () -> authenticator.authenticate(token));
        assertEquals(1, first.calls);
        assertEquals(1, second.calls);
    }

    @Test public void twoRealmStrategyRejectsNullEmptyAndSingleRealmAggregates() {
        AtLeastTwoAuthenticatorStrategy strategy = new AtLeastTwoAuthenticatorStrategy();
        assertThrows(AuthenticationException.class, () -> strategy.afterAllAttempts(token, null));
        AuthenticationInfo empty = strategy.beforeAllAttempts(Collections.emptyList(), token);
        assertThrows(AuthenticationException.class, () -> strategy.afterAllAttempts(token, empty));
        assertThrows(AuthenticationException.class, () -> strategy.afterAllAttempts(token, info("single")));
    }

    @Test public void wrongCredentialsReachRetryBoundaryAndCorrectCredentialsRemainBlocked() {
        MatcherFixture fixture = new MatcherFixture();
        AuthenticationToken wrong = new UsernamePasswordToken("alice", "wrong");
        for (int attempt = 1; attempt < fixture.matcher.getRetryLimit(); attempt++) {
            assertFalse(fixture.matcher.doCredentialsMatch(wrong, info("realm")));
            assertEquals(attempt, fixture.cache.get("alice").get());
        }
        assertThrows(ExcessiveAttemptsException.class,
                () -> fixture.matcher.doCredentialsMatch(wrong, info("realm")));
        assertThrows(ExcessiveAttemptsException.class,
                () -> fixture.matcher.doCredentialsMatch(token, info("realm")));
    }

    @Test public void successClearsOnlyThatPrincipalsRetryState() {
        MatcherFixture fixture = new MatcherFixture();
        assertFalse(fixture.matcher.doCredentialsMatch(new UsernamePasswordToken("alice", "bad"), info("realm")));
        assertFalse(fixture.matcher.doCredentialsMatch(new UsernamePasswordToken("bob", "bad"), info("realm")));
        assertTrue(fixture.matcher.doCredentialsMatch(token, info("realm")));
        assertNull(fixture.cache.get("alice"));
        assertEquals(1, fixture.cache.get("bob").get());
        assertFalse(fixture.matcher.doCredentialsMatch(new UsernamePasswordToken("alice", "bad"), info("realm")));
        assertEquals(1, fixture.cache.get("alice").get());
    }

    @Test public void byteAndCharacterCredentialsCompareByContent() {
        MatcherFixture fixture = new MatcherFixture();
        AuthenticationInfo account = new SimpleAuthenticationInfo("alice", new byte[]{1, 2, 3}, "realm");
        assertTrue(fixture.matcher.doCredentialsMatch(new UsernamePasswordToken("alice", "secret"), info("realm")));
        AuthenticationToken bytes = new AuthenticationToken() {
            public Object getPrincipal() { return "alice"; }
            public Object getCredentials() { return new byte[]{1, 2, 3}; }
        };
        assertTrue(fixture.matcher.doCredentialsMatch(bytes, account));
        assertFalse(fixture.matcher.doCredentialsMatch(bytes,
                new SimpleAuthenticationInfo("alice", new byte[]{1, 2, 4}, "realm")));
    }

    @Test public void onlyOneStrategyMustRejectTwoSuccessfulRealms() {
        DefaultModularRealmAuthenticator authenticator = authenticator(
                new StubRealm("first", true, info("first")),
                new StubRealm("second", true, info("second")));
        authenticator.setAuthenticationStrategy(new org.apache.shiro.biz.authc.pam.OnlyOneAuthenticatorStrategy());
        assertThrows(AuthenticationException.class, () -> authenticator.authenticate(token));
    }

    private static AuthenticationInfo info(String realm) {
        return new SimpleAuthenticationInfo("alice", "secret", realm);
    }
    private static DefaultModularRealmAuthenticator authenticator(Realm... realms) {
        DefaultModularRealmAuthenticator authenticator = new DefaultModularRealmAuthenticator();
        authenticator.setRealms(Arrays.asList(realms));
        return authenticator;
    }
    private static final class StubRealm implements Realm {
        private final String name;
        private final boolean supported;
        private final AuthenticationInfo result;
        private AuthenticationException failure;
        private int calls;
        private StubRealm(String name, boolean supported, AuthenticationInfo result) {
            this.name = name; this.supported = supported; this.result = result;
        }
        public String getName() { return name; }
        public boolean supports(AuthenticationToken ignored) { return supported; }
        public AuthenticationInfo getAuthenticationInfo(AuthenticationToken ignored) {
            calls++;
            if (failure != null) { throw failure; }
            return result;
        }
    }
    private static final class MatcherFixture {
        private final MapCache<Object, AtomicInteger> cache =
                new MapCache<>("test", new ConcurrentHashMap<>());
        private final DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher();
        private MatcherFixture() {
            matcher.setCacheManager(new org.apache.shiro.cache.CacheManager() {
                @SuppressWarnings("unchecked")
                public <K, V> org.apache.shiro.cache.Cache<K, V> getCache(String name) {
                    assertEquals(matcher.getCredentialsRetryCacheName(), name);
                    return (org.apache.shiro.cache.Cache<K, V>) cache;
                }
            });
        }
    }
}
