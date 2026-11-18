package org.apache.shiro.biz.authc.credential;

import java.io.Serializable;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Shared credential retry state for one configured retry cache namespace.
 * Implementations use the cache provider's atomic operations and expiration policy.
 */
public interface CredentialRetryStore {

    /**
     * Atomically starts or increments the current window for a principal. Creation uses
     * the configured expiration policy and an independent window identifier. Incrementing
     * an existing window preserves its expiration and saturates at {@link Integer#MAX_VALUE}.
     *
     * @param principal stable key for the principal in this cache namespace
     * @return the window identifier and count after reserving this attempt
     */
    Attempt reserve(Object principal);

    /**
     * Atomically clears the principal's window if it still has the specified identifier.
     *
     * @param principal stable key for the principal in this cache namespace
     * @param windowId identifier returned by the attempt reservation
     */
    void clearIfCurrent(Object principal, String windowId);

    /** Data returned by a successful reservation. */
    @Getter
    @EqualsAndHashCode
    final class Attempt implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String windowId;
        private final int count;

        /**
         * @param windowId identifier unique to this principal's retry window
         * @param count attempt count after the reservation, starting at one
         */
        public Attempt(String windowId, int count) {
            this.windowId = Objects.requireNonNull(windowId, "windowId");
            if (count < 1) {
                throw new IllegalArgumentException("Attempt count must be positive");
            }
            this.count = count;
        }
    }
}
