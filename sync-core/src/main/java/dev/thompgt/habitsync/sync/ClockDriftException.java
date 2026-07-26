package dev.thompgt.habitsync.sync;

/**
 * Thrown when an inbound {@link Hlc} claims a physical time so far ahead of this node's
 * clock that accepting it would be indistinguishable from a misconfigured or malicious
 * peer.
 *
 * <p>An HLC absorbs skew by advancing to match whatever it observes. That is the desired
 * behaviour for milliseconds of jitter and disastrous for a device whose clock is set to
 * 2038: every replica would ratchet its logical clock forward to that value and stay
 * there, permanently starving every honest write of the ability to ever win a conflict.
 *
 * <p>Bounding the absorption keeps one bad clock a local failure instead of a
 * whole-account one.
 */
public class ClockDriftException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Hlc offending;
    private final long localMillis;
    private final long maxDriftMillis;

    public ClockDriftException(Hlc offending, long localMillis, long maxDriftMillis) {
        super("HLC physical time %d is %d ms ahead of local time %d, exceeding the %d ms limit (node %s)"
                .formatted(
                        offending.physicalMillis(),
                        offending.physicalMillis() - localMillis,
                        localMillis,
                        maxDriftMillis,
                        offending.nodeId()));
        this.offending = offending;
        this.localMillis = localMillis;
        this.maxDriftMillis = maxDriftMillis;
    }

    public Hlc offending() {
        return offending;
    }

    public long localMillis() {
        return localMillis;
    }

    public long maxDriftMillis() {
        return maxDriftMillis;
    }
}
