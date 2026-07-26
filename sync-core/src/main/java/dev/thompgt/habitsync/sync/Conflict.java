package dev.thompgt.habitsync.sync;

import java.util.Objects;

/**
 * A report that merging a pulled page discarded or hid somebody's work.
 *
 * <p>ADR-001 accepts that last-writer-wins loses data when two devices edit one field
 * concurrently. ADR-003 accepts that a tombstone hides a concurrent edit. Both acceptances
 * are conditional, and the condition is the same: <b>the loss is shown to the user rather
 * than swallowed.</b> Silent data loss is a bug; visible, explained loss under a documented
 * rule is a trade-off. This type is what makes that difference deliverable — without it the
 * merge engine's verdicts die inside {@link SyncEngine} and no UI can ever mention them.
 *
 * <h2>Whose loss it was</h2>
 *
 * {@link #lostLocalWrite()} separates the write the user typed on <em>this</em> device from
 * one that some other replica lost. Only the former is worth interrupting anybody about;
 * the rest is debug-screen material. Provenance is exact rather than guessed — an
 * {@link Hlc} carries the id of the node that stamped it, so a value written here is
 * distinguishable from one that merely arrived here earlier and has sat in the register
 * since.
 *
 * @param entity         the entity whose work was lost
 * @param kind           the shape of the loss; see {@link Kind}
 * @param field          the contested field, or {@code null} when the contest was not
 *                       about one
 * @param winner         the clock of the write that stands
 * @param loser          the clock of the write that was discarded or hidden
 * @param lostLocalWrite the losing write was stamped by this device
 */
public record Conflict(
        EntityKey entity, Kind kind, String field, Hlc winner, Hlc loser, boolean lostLocalWrite) {

    /**
     * The three ways a merge costs somebody work.
     *
     * <p>They are distinguished because they read differently to a user, not because the
     * engine treats them differently. "Your rename was overwritten" and "the habit you were
     * renaming no longer exists" call for different wording, and a client that could only
     * say "conflict" would be useless.
     */
    public enum Kind {
        /** Two writes reached one field register; the older one was dropped. */
        FIELD_OVERWRITTEN,

        /** A DELETE and a RESTORE contended for the lifecycle register. */
        LIFECYCLE_CONTESTED,

        /**
         * A delete from another device hid an entity holding writes this device authored.
         *
         * <p>The scenario ADR-003 opens with: one device deletes "Evening Run" while
         * another renames it. Note there is no <em>register</em> contest here — the
         * lifecycle register was unset, so the delete applied unopposed, and the field
         * writes survive untouched in their own registers. The loss is one of
         * <em>visibility</em>, which is exactly why it needs reporting separately: the
         * merge engine, correctly, saw nothing worth remarking on.
         */
        HIDDEN_BY_DELETE
    }

    public Conflict {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(winner, "winner");
        Objects.requireNonNull(loser, "loser");
        if (kind == Kind.FIELD_OVERWRITTEN && field == null) {
            throw new IllegalArgumentException("A field conflict must name its field");
        }
        if (kind != Kind.FIELD_OVERWRITTEN && field != null) {
            throw new IllegalArgumentException("Only a field conflict may name a field");
        }
    }

    /**
     * Builds a report from a merge decision, or empty when nothing was displaced.
     *
     * <p>A write into an unset register is the ordinary case, not a conflict. Reporting
     * those would bury the real ones under every first write the device ever pulls.
     *
     * @param nodeId this device's node id, which decides {@link #lostLocalWrite()}
     */
    static java.util.Optional<Conflict> from(EntityKey entity, Resolution resolution, String nodeId) {
        if (resolution.existing() == null) {
            return java.util.Optional.empty();
        }
        if (resolution.existing().equals(resolution.incoming())) {
            // The same write arriving a second time — a duplicated page, or this device
            // pulling back an op it pushed a moment ago. Merge treats that as idempotence
            // and so must this: an at-least-once network would otherwise manufacture a
            // "conflict" notice out of every successful round trip.
            return java.util.Optional.empty();
        }
        boolean incomingLost = resolution.lostConflict();
        if (incomingLost && resolution.incoming().nodeId().equals(nodeId)) {
            // An op this device stamped, coming back down the pull stream and losing to a
            // write that already displaced it. The loss was reported when the winner
            // arrived; reporting it again on the echo would double-count every contested
            // field, because the server's log carries our own ops back to us.
            return java.util.Optional.empty();
        }
        Hlc winner = incomingLost ? resolution.existing() : resolution.incoming();
        Hlc loser = incomingLost ? resolution.incoming() : resolution.existing();
        Kind kind =
                resolution.target() == Resolution.Target.FIELD
                        ? Kind.FIELD_OVERWRITTEN
                        : Kind.LIFECYCLE_CONTESTED;
        return java.util.Optional.of(
                new Conflict(entity, kind, resolution.field(), winner, loser, loser.nodeId().equals(nodeId)));
    }

    /**
     * Reports that {@code deletedBy} hid an entity carrying this device's own writes.
     *
     * @param hiddenWrite the newest field write this device authored on the entity
     */
    static Conflict hiddenByDelete(EntityKey entity, Hlc deletedBy, Hlc hiddenWrite) {
        return new Conflict(entity, Kind.HIDDEN_BY_DELETE, null, deletedBy, hiddenWrite, true);
    }
}
