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

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.pam.AbstractAuthenticationStrategy;
import org.apache.shiro.biz.utils.StringUtils2;
import org.apache.shiro.realm.Realm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * 唯一认证策略实现
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 */
public class OnlyOneAuthenticatorStrategy extends AbstractAuthenticationStrategy {

	protected static Logger LOG = LoggerFactory.getLogger(OnlyOneAuthenticatorStrategy.class);

	/** 在所有Realm验证之前调用 */
	@Override
	public AuthenticationInfo beforeAllAttempts(Collection<? extends Realm> realms, AuthenticationToken token)
			throws AuthenticationException {
		// 返回一个权限的认证信息
		return new RealmAuthenticationInfo();
	}

	/** 在每个Realm之前调用 */
	@Override
	public AuthenticationInfo beforeAttempt(Realm realm, AuthenticationToken token, AuthenticationInfo aggregate)
			throws AuthenticationException {
		// 返回之前合并的
		return aggregate;
	}

	/**
	 * 在每个Realm之后调用
	 */
	@Override
	public AuthenticationInfo afterAttempt(Realm realm, AuthenticationToken token, AuthenticationInfo singleRealmInfo,
			AuthenticationInfo aggregateInfo, Throwable t) throws AuthenticationException {
        // A non-null realm result must identify an account. Reject malformed
        // results explicitly rather than leaking a null pointer from aggregation.
        if (singleRealmInfo != null && (singleRealmInfo.getPrincipals() == null
                || singleRealmInfo.getPrincipals().isEmpty())) {
            throw new AuthenticationException("Realm returned authentication information without principals.");
        }
		AuthenticationInfo info;
		if (singleRealmInfo == null) {
			info = aggregateInfo;
		} else {
			if (aggregateInfo == null) {
				info = singleRealmInfo;
			} else {
				info = merge(singleRealmInfo, aggregateInfo);
				if (info.getPrincipals().getRealmNames().size() > 1
                        || (info instanceof RealmAuthenticationInfo
                        && ((RealmAuthenticationInfo) info).getSuccessfulRealms() > 1)) {
					LOG.debug("RealmNames:" + StringUtils2.join(info.getPrincipals().getRealmNames(), ","));
					throw new AuthenticationException("Authentication token of type [" + token.getClass() + "] "
							+ "could not be authenticated by any configured realms.  Please ensure that only one realm can "
							+ "authenticate these tokens.");
				}
			}
		}
		return info;
	}

	/** 在所有Realm之后调用 */
	@Override
	public AuthenticationInfo afterAllAttempts(AuthenticationToken token, AuthenticationInfo aggregate)
			throws AuthenticationException {
        if (aggregate == null || aggregate.getPrincipals() == null
                || aggregate.getPrincipals().isEmpty()
                || aggregate.getPrincipals().getRealmNames().size() != 1
                || (aggregate instanceof RealmAuthenticationInfo
                    && ((RealmAuthenticationInfo) aggregate).getSuccessfulRealms() != 1)) {
            throw new AuthenticationException("Exactly one realm must authenticate the token.");
        }
        return aggregate;
    }

}
