package org.apache.shiro.biz.authc.credential;

import java.util.function.BooleanSupplier;
import org.apache.shiro.authc.ExcessiveAttemptsException;

final class CredentialRetrySupport {

    private CredentialRetrySupport() {
    }

    static boolean match(CredentialRetryStore store, Object principal, int limit, BooleanSupplier credentialsMatch) {
        CredentialRetryStore.Attempt attempt = store.reserve(principal);
        if (attempt.getCount() >= limit) {
            throw new ExcessiveAttemptsException();
        }
        boolean matches = credentialsMatch.getAsBoolean();
        if (matches) {
            store.clearIfCurrent(principal, attempt.getWindowId());
        }
        return matches;
    }
}
