package dev.thompgt.habitsync.sync;

import java.time.Duration;
import java.util.Objects;

/**
 * A node's Hybrid Logical Clock, implementing the send/receive rules from Kulkarni et al.
 *
 * <p>Two operations drive it:
 *
 * <ul>
 *   <li>{@link #tick()} — stamp a locally originated event.
 *   <li>{@link #observe(Hlc)} — fold in a timestamp received from a peer, so that any
 *       event this node subsequently stamps is ordered strictly after everything it has
 *       seen. This is what makes the clock <em>causal</em> rather than merely a
 *       timestamp generator, and it must be called for every inbound change.
 * </ul>
 *
 * <p>Thread-safe: all mutation happens under the instance monitor. Contention is
 * negligible (a phone's sync worker plus its UI thread) and the critical section is a
 * handful of comparisons, so a lock is the right trade against the subtle CAS-retry
 * logic a lock-free version would need.
 */
public final class HlcClock {

    /** Default tolerance for a peer whose wall clock runs ahead of ours. */
    public static final Duration DEFAULT_MAX_DRIFT = Duration.ofMinutes(5);

    private final String nodeId;
    private final TimeSource timeSource;
    private final long maxDriftMillis;

    private long physicalMillis;
    private int logical;

    public HlcClock(String nodeId, TimeSource timeSource) {
        this(nodeId, timeSource, DEFAULT_MAX_DRIFT);
    }

    public HlcClock(String nodeId, TimeSource timeSource, Duration maxDrift) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.maxDriftMillis = Objects.requireNonNull(maxDrift, "maxDrift").toMillis();
        if (maxDriftMillis < 0) {
            throw new IllegalArgumentException("maxDrift must not be negative");
        }
        // Validate the node id eagerly rather than at first tick(), so a bad id fails at
        // construction instead of hours later on the first sync.
        new Hlc(0L, 0, nodeId);
    }

    public String nodeId() {
        return nodeId;
    }

    /**
     * Stamps a locally originated event.
     *
     * <p>Guaranteed strictly greater than every timestamp this clock has previously
     * returned or observed, so a device's own edits are always ordered by their real
     * sequence even when its wall clock stalls or jumps backwards.
     */
    public synchronized Hlc tick() {
        long now = timeSource.currentTimeMillis();
        if (now > physicalMillis) {
            physicalMillis = now;
            logical = 0;
        } else {
            // Clock stalled within the same millisecond, or jumped backwards. Hold the
            // high-water physical time and advance logically so monotonicity survives.
            logical = incrementLogical(logical);
        }
        return new Hlc(physicalMillis, logical, nodeId);
    }

    /**
     * Folds a peer's timestamp into this clock and returns the clock's new value.
     *
     * <p>Must be called for every inbound change before that change is merged. Skipping
     * it lets this node later stamp an edit with a timestamp lower than one it has
     * already seen, which silently makes fresh local edits lose to stale remote ones.
     *
     * @throws ClockDriftException if {@code remote} is more than the configured drift
     *                             tolerance ahead of local time
     */
    public synchronized Hlc observe(Hlc remote) {
        Objects.requireNonNull(remote, "remote");
        long now = timeSource.currentTimeMillis();

        if (remote.physicalMillis() > now + maxDriftMillis) {
            throw new ClockDriftException(remote, now, maxDriftMillis);
        }

        long maxPhysical = Math.max(now, Math.max(physicalMillis, remote.physicalMillis()));

        if (maxPhysical == physicalMillis && maxPhysical == remote.physicalMillis()) {
            // Both clocks agree on the millisecond: take the higher counter and step past it.
            logical = incrementLogical(Math.max(logical, remote.logical()));
        } else if (maxPhysical == physicalMillis) {
            logical = incrementLogical(logical);
        } else if (maxPhysical == remote.physicalMillis()) {
            logical = incrementLogical(remote.logical());
        } else {
            // Local wall clock leads both; it alone is enough to order this event.
            logical = 0;
        }
        physicalMillis = maxPhysical;

        return new Hlc(physicalMillis, logical, nodeId);
    }

    /** @return the current value without advancing the clock. */
    public synchronized Hlc peek() {
        return new Hlc(physicalMillis, logical, nodeId);
    }

    private static int incrementLogical(int current) {
        if (current == Integer.MAX_VALUE) {
            // Requires ~2 billion events inside one stalled millisecond. If it ever
            // happens, something is badly wrong upstream and silently wrapping to a
            // lower counter would corrupt the total order.
            throw new IllegalStateException(
                    "HLC logical counter overflow: the physical clock has not advanced in "
                            + Integer.MAX_VALUE + " events");
        }
        return current + 1;
    }
}
