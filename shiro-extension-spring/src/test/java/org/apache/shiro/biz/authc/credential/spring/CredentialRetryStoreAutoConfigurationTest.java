package org.apache.shiro.biz.authc.credential.spring;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.authc.ExcessiveAttemptsException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.authc.credential.CredentialsRetryLimitCredentialsMatcher;
import org.apache.shiro.biz.authc.credential.DefaultCredentialsMatcher;
import org.apache.shiro.cache.MemoryConstrainedCacheManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.junit.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class CredentialRetryStoreAutoConfigurationTest {

    @Test
    public void bootDiscoversBindingsForBothMatcherTypesByTheirCacheNames() {
        RecordingStore first = new RecordingStore();
        RecordingStore second = new RecordingStore();
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean(storeName("password"), CredentialRetryStore.class, () -> first);
            context.registerBean(storeName("hashed"), CredentialRetryStore.class, () -> second);
            context.registerBean("passwordMatcher", DefaultCredentialsMatcher.class, () -> passwordMatcher("password"));
            context.registerBean(CredentialsRetryLimitCredentialsMatcher.class, this::hashedMatcher);
            context.refresh();

            DefaultCredentialsMatcher password = context.getBean(DefaultCredentialsMatcher.class);
            CredentialsRetryLimitCredentialsMatcher hashed = context.getBean(CredentialsRetryLimitCredentialsMatcher.class);
            assertSame(first, password.getCredentialRetryStore());
            assertSame(second, hashed.getCredentialRetryStore());
            assertFalse(password.doCredentialsMatch(
                    new UsernamePasswordToken("principal", "wrong"),
                    new SimpleAuthenticationInfo("principal", "correct", "realm")));
            assertEquals(1, first.attempts.get());
            assertEquals(0, second.attempts.get());
        }
    }

    @Test
    public void keepsExplicitStoreAndUnconfiguredMatchers() {
        RecordingStore assigned = new RecordingStore();
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean(storeName("manual"), CredentialRetryStore.class, RecordingStore::new);
            context.registerBean("manual", DefaultCredentialsMatcher.class, () -> {
                DefaultCredentialsMatcher matcher = passwordMatcher("manual");
                matcher.setCredentialRetryStore(assigned);
                return matcher;
            });
            context.registerBean("legacy", DefaultCredentialsMatcher.class, () -> {
                DefaultCredentialsMatcher matcher = passwordMatcher("legacy");
                matcher.setCacheManager(new MemoryConstrainedCacheManager());
                matcher.setRetryLimit(2);
                return matcher;
            });
            context.registerBean(AllowAllCredentialsMatcher.class, AllowAllCredentialsMatcher::new);
            context.refresh();

            DefaultCredentialsMatcher manual = context.getBean("manual", DefaultCredentialsMatcher.class);
            assertSame(assigned, manual.getCredentialRetryStore());

            DefaultCredentialsMatcher legacy = context.getBean("legacy", DefaultCredentialsMatcher.class);
            assertNull(legacy.getCredentialRetryStore());
            UsernamePasswordToken rejected = new UsernamePasswordToken("principal", "wrong");
            SimpleAuthenticationInfo account = new SimpleAuthenticationInfo("principal", "correct", "realm");
            assertFalse(legacy.doCredentialsMatch(rejected, account));
            assertThrows(ExcessiveAttemptsException.class, () -> legacy.doCredentialsMatch(rejected, account));

            context.getBean(AllowAllCredentialsMatcher.class);
        }
    }

    @Test
    public void bindsAnInlineMatcherThroughItsSpringManagedRealm() {
        RecordingStore store = new RecordingStore();
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean(storeName("inline"), CredentialRetryStore.class, () -> store);
            context.registerBean(SimpleAccountRealm.class, () -> {
                SimpleAccountRealm realm = new SimpleAccountRealm();
                DefaultCredentialsMatcher matcher = passwordMatcher("inline");
                matcher.setRetryLimit(2);
                realm.setCredentialsMatcher(matcher);
                realm.addAccount("principal", "correct");
                return realm;
            });
            context.refresh();

            SimpleAccountRealm realm = context.getBean(SimpleAccountRealm.class);
            UsernamePasswordToken rejected = new UsernamePasswordToken("principal", "wrong");
            assertThrows(IncorrectCredentialsException.class, () -> realm.getAuthenticationInfo(rejected));
            assertThrows(ExcessiveAttemptsException.class, () -> realm.getAuthenticationInfo(rejected));
            assertEquals(2, store.attempts.get());
        }
    }

    @Test
    public void reportsARegionBeanWithTheWrongTypeAtStartup() {
        try (AnnotationConfigApplicationContext context = context()) {
            context.registerBean(storeName("password"), String.class, () -> "incorrect type");
            context.registerBean(DefaultCredentialsMatcher.class, () -> passwordMatcher("password"));
            BeanCreationException failure = assertThrows(BeanCreationException.class, context::refresh);
            assertEquals(BeanNotOfRequiredTypeException.class, failure.getMostSpecificCause().getClass());
        }
    }

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(BootConfiguration.class);
        return context;
    }

    private static String storeName(String region) {
        return CredentialRetryStoreAutoConfiguration.STORE_BEAN_PREFIX + region;
    }

    private static DefaultCredentialsMatcher passwordMatcher(String region) {
        DefaultCredentialsMatcher matcher = new DefaultCredentialsMatcher();
        matcher.setCredentialsRetryCacheName(region);
        return matcher;
    }

    private CredentialsRetryLimitCredentialsMatcher hashedMatcher() {
        CredentialsRetryLimitCredentialsMatcher matcher = new CredentialsRetryLimitCredentialsMatcher();
        matcher.setCredentialsRetryCacheName("hashed");
        return matcher;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = RedisAutoConfiguration.class)
    static class BootConfiguration {
    }

    private static final class RecordingStore implements CredentialRetryStore {

        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public Attempt reserve(Object principal) {
            return new Attempt("window", attempts.incrementAndGet());
        }

        @Override
        public void clearIfCurrent(Object principal, String windowId) {
            attempts.set(0);
        }
    }
}
