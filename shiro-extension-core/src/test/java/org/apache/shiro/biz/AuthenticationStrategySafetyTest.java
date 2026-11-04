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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.authc.*;
import org.apache.shiro.authc.pam.AbstractAuthenticationStrategy;
import org.apache.shiro.biz.authc.pam.*;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.*;
import org.apache.shiro.subject.Subject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthenticationStrategySafetyTest {
    private final UsernamePasswordToken token = new UsernamePasswordToken("alice", "secret");

    @Test public void twoRealmPolicyRejectsSingleSupportingPasswordRealmAtSubjectLogin() {
        DefaultModularRealmAuthenticator authenticator = auth(passwordRealm("password"), new SwitchableRealm("other", false, false));
        authenticator.setAuthenticationStrategy(new AtLeastTwoAuthenticatorStrategy());
        assertSubjectRejected(authenticator);
    }
    @Test public void twoRealmPolicyRejectsZeroOrOneSuccessAndAcceptsTwo() {
        for (int successes = 0; successes <= 2; successes++) {
            DefaultModularRealmAuthenticator authenticator = auth(new SwitchableRealm("one", true, successes > 0), new SwitchableRealm("two", true, successes > 1));
            authenticator.setAuthenticationStrategy(new AtLeastTwoAuthenticatorStrategy());
            if (successes < 2) assertSubjectRejected(authenticator);
            else assertSubjectAccepted(authenticator);
        }
    }
    @Test public void onlyOneRejectsZeroAndTwoSuccessesButAcceptsOne() {
        for (int successes = 0; successes <= 2; successes++) {
            DefaultModularRealmAuthenticator authenticator = auth(new SwitchableRealm("one", true, successes > 0), new SwitchableRealm("two", true, successes > 1));
            authenticator.setAuthenticationStrategy(new OnlyOneAuthenticatorStrategy());
            if (successes != 1) assertSubjectRejected(authenticator);
            else assertSubjectAccepted(authenticator);
        }
    }
    @Test public void zeroSuccessEmitsFailureAndNeverSuccess() {
        DefaultModularRealmAuthenticator authenticator = auth(new SwitchableRealm("one", true, false), new SwitchableRealm("two", true, false));
        authenticator.setAuthenticationStrategy(new OnlyOneAuthenticatorStrategy());
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        authenticator.setAuthenticationListeners(Collections.singletonList(new AuthenticationListener() {
            public void onSuccess(AuthenticationToken token, AuthenticationInfo info) { successes.incrementAndGet(); }
            public void onFailure(AuthenticationToken token, AuthenticationException failure) { failures.incrementAndGet(); }
            public void onLogout(org.apache.shiro.subject.PrincipalCollection principals) { }
        }));
        assertThrows(AuthenticationException.class, () -> authenticator.authenticate(token));
        assertEquals(0, successes.get());
        assertEquals(1, failures.get());
    }
    @Test public void onlyOneRejectsNullEmptyAndMultipleRealmAggregates() {
        OnlyOneAuthenticatorStrategy strategy = new OnlyOneAuthenticatorStrategy();
        assertThrows(AuthenticationException.class, () -> strategy.afterAllAttempts(token, null));
        assertThrows(AuthenticationException.class, () -> strategy.afterAllAttempts(token, new SimpleAuthenticationInfo()));
        SimpleAuthenticationInfo multiple = new SimpleAuthenticationInfo("alice", "secret", "one");
        multiple.merge(new SimpleAuthenticationInfo("alice", "secret", "two"));
        assertThrows(AuthenticationException.class, () -> strategy.afterAllAttempts(token, multiple));
    }
    @Test public void oneConfiguredRealmKeepsUpstreamNoStrategyContract() {
        DefaultModularRealmAuthenticator authenticator = auth(passwordRealm("one"));
        authenticator.setAuthenticationStrategy(new AtLeastTwoAuthenticatorStrategy());
        assertSubjectAccepted(authenticator);
        assertThrows(IncorrectCredentialsException.class,
                () -> authenticator.authenticate(new UsernamePasswordToken("alice", "wrong")));
    }
    @Test public void configuredStrategiesRunEvenWithOneSupportingRealm() {
        DefaultModularRealmAuthenticator authenticator = auth(passwordRealm("one"), new SwitchableRealm("two", false, false));
        AtomicInteger calls = new AtomicInteger();
        LockedAccountException denied = new LockedAccountException("strategy denied");
        authenticator.setAuthenticationStrategy(new AbstractAuthenticationStrategy() {
            @Override public AuthenticationInfo afterAllAttempts(AuthenticationToken token, AuthenticationInfo aggregate) {
                calls.incrementAndGet(); throw denied;
            }
        });
        assertSame(denied, assertThrows(LockedAccountException.class, () -> authenticator.authenticate(token)));
        assertEquals(1, calls.get());
        authenticator.setAuthenticationStrategy(new OnlyOneAuthenticatorStrategy());
        assertSubjectAccepted(authenticator);
    }
    @Test public void changingRealmSupportBetweenAttemptsDoesNotBypassPolicy() {
        SwitchableRealm second = new SwitchableRealm("two", true, true);
        DefaultModularRealmAuthenticator authenticator = auth(passwordRealm("one"), second);
        authenticator.setAuthenticationStrategy(new AtLeastTwoAuthenticatorStrategy());
        assertSubjectAccepted(authenticator);
        second.supported = false;
        assertSubjectRejected(authenticator);
        second.supported = true;
        assertSubjectAccepted(authenticator);
        authenticator.setRealms(Collections.singletonList(passwordRealm("one")));
        assertSubjectAccepted(authenticator);
    }
    @Test public void unsupportedAndUnknownAccountRemainRejected() {
        for (boolean supported : new boolean[]{false, true}) {
            DefaultModularRealmAuthenticator authenticator = auth(new SwitchableRealm("one", supported, false), new SwitchableRealm("two", supported, false));
            authenticator.setAuthenticationStrategy(new OnlyOneAuthenticatorStrategy());
            assertSubjectRejected(authenticator);
        }
        assertThrows(UnknownAccountException.class, () -> auth(new SimpleAccountRealm("empty")).authenticate(token));
    }
    @Test public void sameNamedRealmsCannotSatisfyOnlyOneByCollapsingTheirNames() {
        DefaultModularRealmAuthenticator authenticator = auth(passwordRealm("same"), passwordRealm("same"));
        authenticator.setAuthenticationStrategy(new OnlyOneAuthenticatorStrategy());
        assertSubjectRejected(authenticator);
    }
    @Test public void multiRealmFailuresFollowStrategyWhileSingleRealmPreservesSpecificException() {
        Realm locked = new Realm() {
            public String getName() { return "locked"; }
            public boolean supports(AuthenticationToken token) { return true; }
            public AuthenticationInfo getAuthenticationInfo(AuthenticationToken token) { throw new LockedAccountException("locked"); }
        };
        assertThrows(LockedAccountException.class, () -> auth(locked).authenticate(token));
        DefaultModularRealmAuthenticator multi = auth(locked, new SwitchableRealm("unsupported", false, false));
        multi.setAuthenticationStrategy(new OnlyOneAuthenticatorStrategy());
        AuthenticationException failure = assertThrows(AuthenticationException.class, () -> multi.authenticate(token));
        assertEquals(AuthenticationException.class, failure.getClass());
    }
    @Test public void aggregationDoesNotMutateCachedPrincipalsCredentialsOrSalt() {
        org.apache.shiro.lang.util.ByteSource salt = org.apache.shiro.lang.util.ByteSource.Util.bytes("test-salt");
        SimpleAuthenticationInfo first = new SimpleAuthenticationInfo("alice", new ArrayList<>(Collections.singletonList("first")), salt, "one");
        SimpleAuthenticationInfo second = new SimpleAuthenticationInfo("alice", "second", "two");
        AtLeastTwoAuthenticatorStrategy strategy = new AtLeastTwoAuthenticatorStrategy();
        AuthenticationInfo aggregate = strategy.beforeAllAttempts(Collections.emptyList(), token);
        aggregate = strategy.afterAttempt(null, token, first, aggregate, null);
        aggregate = strategy.afterAttempt(null, token, second, aggregate, null);
        aggregate = strategy.afterAllAttempts(token, aggregate);
        assertEquals(Collections.singleton("one"), first.getPrincipals().getRealmNames());
        assertEquals(Collections.singletonList("first"), first.getCredentials());
        SimpleAuthenticationInfo upstreamAggregate = new SimpleAuthenticationInfo();
        upstreamAggregate.merge(new SimpleAuthenticationInfo("alice", "first", salt, "one"));
        assertEquals(upstreamAggregate.getCredentialsSalt(), ((SaltedAuthenticationInfo) aggregate).getCredentialsSalt());
        assertEquals(salt, first.getCredentialsSalt());
        assertTrue(((Collection<?>) aggregate.getCredentials()).containsAll(Arrays.asList("first", "second")));
    }
    @Test public void strategyStateIsIsolatedAcrossConcurrentAttempts() throws Exception {
        OnlyOneAuthenticatorStrategy strategy = new OnlyOneAuthenticatorStrategy();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final boolean secondSucceeds = i % 2 == 0;
                results.add(pool.submit(() -> {
                    DefaultModularRealmAuthenticator authenticator = auth(passwordRealm("one"), new SwitchableRealm("two", true, secondSucceeds));
                    authenticator.setAuthenticationStrategy(strategy);
                    try { authenticator.authenticate(token); return !secondSucceeds; }
                    catch (AuthenticationException expected) { return secondSucceeds; }
                }));
            }
            for (java.util.concurrent.Future<Boolean> result : results) assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private void assertSubjectRejected(DefaultModularRealmAuthenticator authenticator) {
        DefaultSecurityManager manager = new DefaultSecurityManager();
        manager.setAuthenticator(authenticator);
        Subject subject = new Subject.Builder(manager).buildSubject();
        try {
            assertThrows(AuthenticationException.class, () -> subject.login(token));
            assertFalse(subject.isAuthenticated());
        } finally { subject.logout(); manager.destroy(); }
    }
    private void assertSubjectAccepted(DefaultModularRealmAuthenticator authenticator) {
        DefaultSecurityManager manager = new DefaultSecurityManager();
        manager.setAuthenticator(authenticator);
        Subject subject = new Subject.Builder(manager).buildSubject();
        try {
            subject.login(token);
            assertTrue(subject.isAuthenticated());
            assertEquals("alice", subject.getPrincipal());
        } finally { subject.logout(); manager.destroy(); }
    }
    private static SimpleAccountRealm passwordRealm(String name) {
        SimpleAccountRealm realm = new SimpleAccountRealm(name);
        realm.addAccount("alice", "secret");
        return realm;
    }
    private static DefaultModularRealmAuthenticator auth(Realm... realms) {
        DefaultModularRealmAuthenticator authenticator = new DefaultModularRealmAuthenticator();
        authenticator.setRealms(Arrays.asList(realms));
        return authenticator;
    }
    private static final class SwitchableRealm implements Realm {
        private final String name; private boolean supported; private final boolean succeeds;
        private SwitchableRealm(String name, boolean supported, boolean succeeds) { this.name=name; this.supported=supported; this.succeeds=succeeds; }
        public String getName() { return name; }
        public boolean supports(AuthenticationToken token) { return supported; }
        public AuthenticationInfo getAuthenticationInfo(AuthenticationToken token) {
            if (!succeeds) throw new IncorrectCredentialsException("local rejection");
            return new SimpleAuthenticationInfo("alice", "secret", name);
        }
    }
}
