package org.apache.shiro.biz.spring;

import jakarta.servlet.Filter;
import org.apache.shiro.biz.web.filter.authz.AbstracAuthorizationFilter;
import org.apache.shiro.web.servlet.AbstractShiroFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiroJakartaServletContractTest {

    @Test
    void shiroFiltersImplementJakartaServletFilter() {
        assertTrue(Filter.class.isAssignableFrom(AbstractShiroFilter.class));
        assertTrue(Filter.class.isAssignableFrom(AbstracAuthorizationFilter.class));
    }
}
