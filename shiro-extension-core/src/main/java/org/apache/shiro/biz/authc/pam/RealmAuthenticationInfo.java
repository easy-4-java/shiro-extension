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
package org.apache.shiro.biz.authc.pam;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.SaltedAuthenticationInfo;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.subject.SimplePrincipalCollection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** An invocation-local aggregate that never mutates a realm's cached principals. */
final class RealmAuthenticationInfo extends SimpleAuthenticationInfo {
    private static final long serialVersionUID = 1L;
    private int successfulRealms;

    int getSuccessfulRealms() {
        return successfulRealms;
    }

    @Override
    public void merge(AuthenticationInfo info) {
        if (info == null || info.getPrincipals() == null || info.getPrincipals().isEmpty()) {
            return;
        }
        SimpleAuthenticationInfo snapshot = new SimpleAuthenticationInfo();
        snapshot.setPrincipals(new SimplePrincipalCollection(info.getPrincipals()));
        Object credentials = info.getCredentials();
        if (credentials instanceof Set<?>) {
            credentials = new LinkedHashSet<>((Set<?>) credentials);
        } else if (credentials instanceof Collection<?>) {
            credentials = new ArrayList<>((Collection<?>) credentials);
        }
        snapshot.setCredentials(credentials);
        if (info instanceof SaltedAuthenticationInfo) {
            snapshot.setCredentialsSalt(((SaltedAuthenticationInfo) info).getCredentialsSalt());
        }
        super.merge(snapshot);
        successfulRealms++;
    }
}
