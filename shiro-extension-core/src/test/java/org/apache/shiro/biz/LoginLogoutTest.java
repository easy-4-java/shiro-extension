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

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.ini.IniSecurityManagerFactory;
import org.apache.shiro.lang.util.Factory;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;
import org.junit.Assert;
import org.junit.Test;

public class LoginLogoutTest {

    @Test
    public void testLoginLogoutAndRejectIncorrectPassword() {
        Factory<SecurityManager> factory = new IniSecurityManagerFactory("classpath:shiro.ini");
        SecurityManager securityManager = factory.getInstance();
        SecurityUtils.setSecurityManager(securityManager);
        Subject subject = SecurityUtils.getSubject();
        try {
            try {
                subject.login(new UsernamePasswordToken("zhang", "incorrect"));
                Assert.fail("Incorrect credentials must be rejected");
            } catch (AuthenticationException expected) {
                Assert.assertFalse(subject.isAuthenticated());
            }
            subject.login(new UsernamePasswordToken("zhang", "123"));
            Assert.assertTrue(subject.isAuthenticated());
            subject.logout();
            Assert.assertFalse(subject.isAuthenticated());
        } finally {
            subject.logout();
            org.apache.shiro.util.ThreadContext.remove();
            SecurityUtils.setSecurityManager(null);
            org.apache.shiro.lang.util.LifecycleUtils.destroy(securityManager);
        }
    }
}
