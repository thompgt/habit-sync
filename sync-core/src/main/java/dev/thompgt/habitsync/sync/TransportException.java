package dev.thompgt.habitsync.sync;

/**
 * A sync round trip did not complete: no connectivity, a timeout, a 5xx, a malformed
 * response.
 *
 * <p>Checked, not unchecked, and that is the point. On an offline-first client a failed
 * sync is the <em>expected</em> case, not an exceptional one — the app spends most of
 * its life on a train. Making it checked forces every caller to decide what happens next
 * rather than letting a failure unwind into a crash reporter.
 *
 * <p>{@code retryable} distinguishes "the network was rubbish" from "this device's
 * credentials are gone" — the first wants backoff, the second wants the user.
 */
public class TransportException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public TransportException(String message) {
        this(message, null, true);
    }

    public TransportException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public TransportException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** @return whether retrying later could plausibly succeed without user intervention. */
    public boolean isRetryable() {
        return retryable;
    }
}
