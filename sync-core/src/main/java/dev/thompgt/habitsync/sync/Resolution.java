package dev.thompgt.habitsync.sync;

import java.util.Objects;

/**
 * The record of a single conflict decision — what the engine was asked to do, what it
 * decided, and the two clock readings behind the decision.
 *
 * <p>Merge returns these rather than logging them, because sync-core has no logger and
 * because "who won and why" is information the server, the client, and the tests all
 * want in different forms: the server records it as a metric, the client surfaces it to
 * the user when their edit was discarded, and tests assert on it directly.
 *
 * <p>Discarded work that the user is never told about is a bug. Discarded work under a
 * documented rule, reported, is a trade-off. These records are what make the difference.
 *
 * @param target   which register was contested
 * @param field    the field name for {@link Target#FIELD}; {@code null} for
 *                 {@link Target#LIFECYCLE}
 * @param verdict  whether the incoming change took effect
 * @param incoming the incoming change's clock
 * @param existing the clock already in place, or {@code null} if the register was unset
 */
public record Resolution(Target target, String field, Verdict verdict, Hlc incoming, Hlc existing) {

    /** Which of an entity's two independent register groups a resolution concerns. */
    public enum Target {
        FIELD,
        LIFECYCLE
    }

    /** The outcome of comparing an incoming clock against the one in place. */
    public enum Verdict {
        /** The incoming change won and was written. */
        APPLIED,
        /** The register already held a strictly greater clock; the incoming write was dropped. */
        SUPERSEDED
    }

    public Resolution {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(incoming, "incoming");
        if (target == Target.FIELD && field == null) {
            throw new IllegalArgumentException("FIELD resolutions must name a field");
        }
        if (target == Target.LIFECYCLE && field != null) {
            throw new IllegalArgumentException("LIFECYCLE resolutions must not name a field");
        }
    }

    static Resolution field(String field, Verdict verdict, Hlc incoming, Hlc existing) {
        return new Resolution(Target.FIELD, field, verdict, incoming, existing);
    }

    static Resolution lifecycle(Verdict verdict, Hlc incoming, Hlc existing) {
        return new Resolution(Target.LIFECYCLE, null, verdict, incoming, existing);
    }

    /** True when a write was dropped in favour of an existing, newer one. */
    public boolean lostConflict() {
        return verdict == Verdict.SUPERSEDED;
    }
}
