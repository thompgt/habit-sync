package dev.thompgt.habitsync.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a {@link Change} against an {@link EntityRecord}. This is the heart of the
 * system, and everything else exists to feed it or to store what it returns.
 *
 * <p>The engine is a <b>pure function</b> — no I/O, no clock reads, no logging, no
 * state. That is a deliberate constraint, and it buys three things:
 *
 * <ul>
 *   <li>The server and the Android client run this identical class, so they cannot
 *       disagree about who won a conflict. Client/server merge divergence is the most
 *       common failure mode in hand-rolled sync, and this removes the possibility
 *       rather than testing for it.
 *   <li>Its algebraic properties can be property-tested directly, with no fixtures.
 *   <li>The convergence simulator can drive the real engine on a plain JVM, so what the
 *       tests exercise is the production code path, not a model of it.
 * </ul>
 *
 * <h2>The three properties merge must satisfy</h2>
 *
 * The network delivers changes out of order, more than once, and split across retries.
 * Merge therefore has to be:
 *
 * <ul>
 *   <li><b>Commutative</b> — {@code merge(a, merge(b, s)) == merge(b, merge(a, s))}.
 *       Two devices receiving the same changes in different orders must agree.
 *   <li><b>Idempotent</b> — {@code merge(a, merge(a, s)) == merge(a, s)}. A duplicated
 *       delivery or a replayed push must not double-apply.
 *   <li><b>Associative</b> — grouping is irrelevant, so changes can be batched into
 *       pages however the transport finds convenient.
 * </ul>
 *
 * All three follow from resolving every register by taking the maximum HLC, and from
 * {@link Hlc} being a total order. They are asserted directly by
 * {@code MergePropertyTest}.
 *
 * @see <a href="../../../../../../docs/adr/001-conflict-resolution-model.md">ADR-001</a>
 */
public final class MergeEngine {

    /**
     * Merges {@code change} into {@code current}.
     *
     * @param current the entity's present state; pass {@link EntityRecord#empty} (or use
     *                {@link #merge(EntityRecord, Change)}'s null handling) for an entity
     *                this replica has not seen before
     * @param change  the change to apply
     * @return the resulting state and the decisions taken
     * @throws IllegalArgumentException if the change targets a different entity
     */
    public MergeResult merge(EntityRecord current, Change change) {
        Objects.requireNonNull(change, "change");

        EntityRecord base = current != null ? current : EntityRecord.empty(change.key());
        if (!base.key().equals(change.key())) {
            throw new IllegalArgumentException(
                    "Change targets %s but record is %s".formatted(change.key(), base.key()));
        }

        return switch (change.kind()) {
            case UPSERT -> mergeFields(base, change);
            case DELETE -> mergeLifecycle(base, change, true);
            case RESTORE -> mergeLifecycle(base, change, false);
        };
    }

    /**
     * Per-field last-writer-wins.
     *
     * <p>Each field is an independent register. One device renaming a habit and another
     * changing its weekly target touch disjoint registers, so both edits survive —
     * whereas per-<em>row</em> LWW would silently discard one of them. That distinction
     * is the single most valuable property of this design.
     *
     * <p>Note this runs unconditionally, including on tombstoned entities. See the class
     * comment on {@link EntityRecord} for why coupling the two register groups would
     * break commutativity.
     */
    private MergeResult mergeFields(EntityRecord base, Change change) {
        EntityRecord next = base;
        List<Resolution> resolutions = new ArrayList<>(change.fields().size());

        for (Map.Entry<String, FieldValue> entry : change.fields().entrySet()) {
            String field = entry.getKey();
            Hlc existing = base.clockOf(field);

            // Strictly-greater, not greater-or-equal. Equality means the very same write
            // arriving twice, and re-applying it must be a no-op for idempotence. Because
            // Hlc's order is total and includes nodeId, equal clocks imply equal writes.
            if (existing == null || change.hlc().isAfter(existing)) {
                next = next.withField(field, entry.getValue(), change.hlc());
                resolutions.add(
                        Resolution.field(field, Resolution.Verdict.APPLIED, change.hlc(), existing));
            } else {
                resolutions.add(
                        Resolution.field(field, Resolution.Verdict.SUPERSEDED, change.hlc(), existing));
            }
        }

        return new MergeResult(next, resolutions);
    }

    /**
     * The lifecycle register is itself last-writer-wins: whichever of DELETE and RESTORE
     * carries the higher HLC takes effect.
     *
     * <p>This is what makes deletion terminal without making it irreversible. A tombstone
     * is never cleared as a side effect of some later field edit arriving — only an
     * explicit {@link OpKind#RESTORE}, stamped later than the delete, brings an entity
     * back. Users get deletes that stay deleted, and an undo that works.
     */
    private MergeResult mergeLifecycle(EntityRecord base, Change change, boolean deleting) {
        Hlc existing = base.lifecycleClock();

        if (existing == null || change.hlc().isAfter(existing)) {
            return new MergeResult(
                    base.withLifecycle(deleting, change.hlc()),
                    List.of(Resolution.lifecycle(Resolution.Verdict.APPLIED, change.hlc(), existing)));
        }
        return new MergeResult(
                base,
                List.of(Resolution.lifecycle(Resolution.Verdict.SUPERSEDED, change.hlc(), existing)));
    }

    /**
     * Folds a batch of changes for a single entity into one state.
     *
     * <p>Order-insensitive by construction, so callers need not sort the batch — though
     * sorting by HLC first does reduce intermediate allocations.
     */
    public EntityRecord mergeAll(EntityRecord current, Iterable<Change> changes) {
        EntityRecord state = current;
        for (Change change : changes) {
            state = merge(state, change).state();
        }
        return state != null ? state : null;
    }
}
