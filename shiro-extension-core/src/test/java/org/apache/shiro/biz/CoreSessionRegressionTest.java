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

import java.io.*;
import java.util.Date;
import org.apache.shiro.session.ExpiredSessionException;
import org.apache.shiro.session.StoppedSessionException;
import org.apache.shiro.session.mgt.SimpleOnlineSession;
import org.apache.shiro.session.mgt.SimpleOnlineSession.OnlineStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise the custom session serialization used by persisted sessions. */
public class CoreSessionRegressionTest {
    @Test public void roundTripPreservesOnlineHiddenAndForcedLogoutStates() throws Exception {
        for (OnlineStatus status : OnlineStatus.values()) {
            SimpleOnlineSession original = new SimpleOnlineSession("client-host");
            original.setId("session-1");
            original.setUserAgent("local-test-agent");
            original.setSystemHost("server-host");
            original.setStatus(status);
            original.setAttribute("principal", "alice");
            original.markAttributeChanged();
            SimpleOnlineSession restored = roundTrip(original);
            assertEquals(status, restored.getStatus());
            assertEquals("local-test-agent", restored.getUserAgent());
            assertEquals("server-host", restored.getSystemHost());
            assertEquals("client-host", restored.getHost());
            assertEquals("session-1", restored.getId());
            assertEquals("alice", restored.getAttribute("principal"));
            assertFalse("dirty state is transient", restored.isAttributeChanged());
        }
    }

    @Test public void roundTripSupportsAbsentOptionalFields() throws Exception {
        SimpleOnlineSession session = new SimpleOnlineSession();
        session.setStatus(null);
        SimpleOnlineSession restored = roundTrip(session);
        assertNull(restored.getStatus());
        assertNull(restored.getUserAgent());
        assertNull(restored.getSystemHost());
    }

    @Test public void stoppedAndExpiredSessionsStayRejectedAfterRoundTrip() throws Exception {
        SimpleOnlineSession stopped = new SimpleOnlineSession();
        stopped.stop();
        SimpleOnlineSession restoredStopped = roundTrip(stopped);
        assertThrows(StoppedSessionException.class, restoredStopped::validate);
        SimpleOnlineSession expired = new SimpleOnlineSession();
        expired.setTimeout(1);
        expired.setLastAccessTime(new Date(0));
        SimpleOnlineSession restoredExpired = roundTrip(expired);
        assertThrows(ExpiredSessionException.class, restoredExpired::validate);
    }

    @Test public void explicitDirtyStateCanBeResetWithoutLosingAttributes() {
        SimpleOnlineSession session = new SimpleOnlineSession();
        assertFalse(session.isAttributeChanged());
        session.setAttribute("principal", "alice");
        session.markAttributeChanged();
        assertTrue(session.isAttributeChanged());
        session.resetAttributeChanged();
        assertFalse(session.isAttributeChanged());
        assertEquals("alice", session.removeAttribute("principal"));
        assertNull(session.getAttribute("principal"));
    }

    private static SimpleOnlineSession roundTrip(SimpleOnlineSession session) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(session);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (SimpleOnlineSession) input.readObject();
        }
    }
}
