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
package org.apache.shiro.biz.session.mgt;

import org.apache.shiro.biz.utils.WebUtils2;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.SessionContext;
import org.apache.shiro.session.mgt.SessionFactory;
import org.apache.shiro.session.mgt.SimpleOnlineSession;
import org.apache.shiro.web.session.mgt.WebSessionContext;
import org.apache.shiro.web.util.WebUtils;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * @author [@Loong Wan](https://github.com/loong10k)
 */
public class SimpleOnlineSessionFactory implements SessionFactory {

    @Override
    public Session createSession(SessionContext initData) {
        if (initData instanceof WebSessionContext) {
            WebSessionContext sessionContext = (WebSessionContext) initData;
            ServletRequest request = sessionContext.getServletRequest();
            if (request instanceof HttpServletRequest) {
                String host = WebUtils2.getRemoteAddr(request);
                SimpleOnlineSession session = new SimpleOnlineSession(host);
                session.setUserAgent(WebUtils.toHttp(request).getHeader("User-Agent"));
                session.setSystemHost(host + ":" + request.getLocalPort());
                return session;
            }
        }
        return new SimpleOnlineSession();
    }
}
