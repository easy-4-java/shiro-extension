package org.apache.shiro.biz.authc.credential.spring;

import java.util.Objects;
import java.util.function.Consumer;
import org.apache.shiro.biz.authc.credential.CredentialRetryStore;
import org.apache.shiro.biz.authc.credential.CredentialsRetryLimitCredentialsMatcher;
import org.apache.shiro.biz.authc.credential.DefaultCredentialsMatcher;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.StringUtils;

/**
 * Applies configured provider-backed stores to Spring-managed retry matchers.
 * Declare a {@link CredentialRetryStore} bean named {@code shiro.credential-retry.<cacheName>}
 * to enable the store for matchers using that cache name.
 */
@AutoConfiguration
public class CredentialRetryStoreAutoConfiguration {

    /** Bean name prefix followed by the matcher's retry cache name. */
    public static final String STORE_BEAN_PREFIX = "shiro.credential-retry.";

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static CredentialRetryStoreBinder shiroCredentialRetryStoreBinder(BeanFactory beanFactory) {
        return new CredentialRetryStoreBinder(beanFactory);
    }

    static final class CredentialRetryStoreBinder implements BeanPostProcessor, PriorityOrdered {

        private final BeanFactory beanFactory;

        CredentialRetryStoreBinder(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            Object target = bean instanceof AuthenticatingRealm realm ? realm.getCredentialsMatcher() : bean;
            if (target instanceof DefaultCredentialsMatcher matcher && Objects.isNull(matcher.getCredentialRetryStore())) {
                bind(matcher.getCredentialsRetryCacheName(), matcher::setCredentialRetryStore);
            } else if (target instanceof CredentialsRetryLimitCredentialsMatcher matcher
                    && Objects.isNull(matcher.getCredentialRetryStore())) {
                bind(matcher.getCredentialsRetryCacheName(), matcher::setCredentialRetryStore);
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        private void bind(String cacheName, Consumer<CredentialRetryStore> setter) {
            if (StringUtils.hasText(cacheName)) {
                String beanName = STORE_BEAN_PREFIX + cacheName;
                if (beanFactory.containsBean(beanName)) {
                    setter.accept(beanFactory.getBean(beanName, CredentialRetryStore.class));
                }
            }
        }
    }
}
