package org.apache.shiro.biz.session.mgt;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.shiro.session.mgt.SimpleOnlineSession;
import org.apache.shiro.web.session.mgt.WebSessionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimpleOnlineSessionFactoryTest {

    @Test
    void preservesRequestHostAndUserAgent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        when(request.getLocalPort()).thenReturn(8080);
        when(request.getHeader("User-Agent")).thenReturn("contract-test");
        WebSessionContext context = mock(WebSessionContext.class);
        when(context.getServletRequest()).thenReturn(request);

        SimpleOnlineSession session = (SimpleOnlineSession) new SimpleOnlineSessionFactory().createSession(context);

        assertEquals("192.0.2.10", session.getHost());
        assertEquals("contract-test", session.getUserAgent());
        assertEquals("192.0.2.10:8080", session.getSystemHost());
    }
}
