package dev.thompgt.habitsync.sync;

import java.util.Objects;

/**
 * A Hybrid Logical Clock timestamp: physical wall-clock time fused with a logical
 * counter, plus the identity of the node that produced it.
 *
 * <p>HLCs give us a <em>total order</em> over events across devices without a central
 * sequencer and without trusting wall clocks:
 *
 * <ul>
 *   <li>The <b>physical</b> component keeps ordering roughly aligned with human
 *       intuition, so "the edit I made later" usually wins.
 *   <li>The <b>logical</b> component preserves causality when the physical clock does
 *       not advance, giving Lamport-clock guarantees: if {@code a} causally precedes
 *       {@code b}, then {@code a.compareTo(b) < 0}.
 *   <li>The <b>nodeId</b> tiebreak is <b>load-bearing</b>. Without it, two events with
 *       identical physical and logical components compare equal, two replicas can pick
 *       different winners, and convergence fails. Do not "simplify" it away — the
 *       convergence simulator deliberately injects its removal as a regression test.
 * </ul>
 *
 * <p>Instances are immutable and safe to share across threads.
 *
 * @param physicalMillis wall-clock milliseconds since the epoch, never negative
 * @param logical        counter disambiguating events within the same millisecond
 * @param nodeId         stable identifier of the originating device; must not contain
 *                       {@value #FIELD_SEPARATOR} so the compact encoding stays parseable
 * @see <a href="https://cse.buffalo.edu/tech-reports/2014-04.pdf">Kulkarni et al.,
 *      "Logical Physical Clocks and Consistent Snapshots in Globally Distributed
 *      Databases" (2014)</a>
 */
public record Hlc(long physicalMillis, int logical, String nodeId) implements Comparable<Hlc> {

    /** Separator used by {@link #toCompactString()}; forbidden inside a node id. */
    public static final String FIELD_SEPARATOR = ":";

    public Hlc {
        if (physicalMillis < 0) {
            throw new IllegalArgumentException("physicalMillis must be >= 0, got " + physicalMillis);
        }
        if (logical < 0) {
            throw new IllegalArgumentException("logical must be >= 0, got " + logical);
        }
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be empty");
        }
        if (nodeId.contains(FIELD_SEPARATOR)) {
            throw new IllegalArgumentException(
                    "nodeId must not contain '" + FIELD_SEPARATOR + "', got: " + nodeId);
        }
    }

    /**
     * Total order: physical time, then logical counter, then node id.
     *
     * <p>Consistent with {@link #equals(Object)}: this returns 0 only for equal values.
     * That consistency is required — HLCs are used as sort keys and map keys, and an
     * ordering that disagreed with equality would make merge results depend on
     * collection implementation details.
     */
    @Override
    public int compareTo(Hlc other) {
        int byPhysical = Long.compare(this.physicalMillis, other.physicalMillis);
        if (byPhysical != 0) {
            return byPhysical;
        }
        int byLogical = Integer.compare(this.logical, other.logical);
        if (byLogical != 0) {
            return byLogical;
        }
        return this.nodeId.compareTo(other.nodeId);
    }

    /** @return {@code true} if this timestamp strictly follows {@code other} in the total order. */
    public boolean isAfter(Hlc other) {
        return compareTo(other) > 0;
    }

    /** @return {@code true} if this timestamp strictly precedes {@code other} in the total order. */
    public boolean isBefore(Hlc other) {
        return compareTo(other) < 0;
    }

    /** @return whichever of the two timestamps is greater; ties (equal values) return {@code a}. */
    public static Hlc max(Hlc a, Hlc b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Encodes as {@code physicalMillis:logical:nodeId} — a single sortable-ish string
     * suitable for one database column or one JSON field.
     *
     * <p>Note this encoding is <em>not</em> lexicographically ordered (physical time is
     * not zero-padded). Always sort by comparing parsed {@link Hlc} values, never by
     * comparing these strings.
     */
    public String toCompactString() {
        return physicalMillis + FIELD_SEPARATOR + logical + FIELD_SEPARATOR + nodeId;
    }

    /** Inverse of {@link #toCompactString()}. */
    public static Hlc parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        // limit=3 so a node id is never split, even though the constructor forbids
        // separators in it — defence in depth against data written by an older version.
        String[] parts = encoded.split(FIELD_SEPARATOR, 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed HLC, expected 'physical:logical:node': " + encoded);
        }
        try {
            return new Hlc(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed HLC, non-numeric component: " + encoded, e);
        }
    }

    @Override
    public String toString() {
        return toCompactString();
    }
}
