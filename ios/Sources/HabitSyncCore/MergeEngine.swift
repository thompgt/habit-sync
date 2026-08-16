import Foundation

/// Resolves a ``Change`` against an ``EntityRecord``. This is the heart of the system, and
/// everything else exists to feed it or to store what it returns.
///
/// The engine is a **pure function** — no I/O, no clock reads, no logging, no state. That
/// is a deliberate constraint, and it buys three things:
///
/// - The server and every client run the same algorithm, so they cannot disagree about who
///   won a conflict. Client/server merge divergence is the most common failure mode in
///   hand-rolled sync.
/// - Its algebraic properties can be property-tested directly, with no fixtures.
/// - It runs on a plain toolchain, so the convergence tests exercise the production path
///   rather than a model of it.
///
/// ## The three properties merge must satisfy
///
/// The network delivers changes out of order, more than once, and split across retries.
/// Merge therefore has to be:
///
/// - **Commutative** — `merge(a, merge(b, s)) == merge(b, merge(a, s))`. Two devices
///   receiving the same changes in different orders must agree.
/// - **Idempotent** — `merge(a, merge(a, s)) == merge(a, s)`. A duplicated delivery or a
///   replayed push must not double-apply.
/// - **Associative** — grouping is irrelevant, so changes can be batched into pages
///   however the transport finds convenient.
///
/// All three follow from resolving every register by taking the maximum HLC, and from
/// ``Hlc`` being a total order.
///
/// > Note: this is a port of the JVM `MergeEngine`. If you change the rule here without
/// > changing it there, the two ends of the protocol will quietly disagree about who won —
/// > which is precisely the class of bug the shared-engine design exists to rule out.
public struct MergeEngine: Sendable {

    public init() {}

    /// Merges `change` into `current`.
    ///
    /// - Parameter current: the entity's present state; `nil` for an entity this replica
    ///   has not seen before.
    public func merge(_ current: EntityRecord?, _ change: Change) -> MergeResult {
        let base = current ?? EntityRecord.empty(key: change.key)
        precondition(
            base.key == change.key,
            "Change targets \(change.key) but record is \(base.key)")

        switch change.kind {
        case .upsert: return mergeFields(base, change)
        case .delete: return mergeLifecycle(base, change, deleting: true)
        case .restore: return mergeLifecycle(base, change, deleting: false)
        }
    }

    /// Per-field last-writer-wins.
    ///
    /// Each field is an independent register. One device renaming a habit and another
    /// changing its weekly target touch disjoint registers, so both edits survive — whereas
    /// per-*row* LWW would silently discard one of them. That distinction is the single
    /// most valuable property of this design.
    ///
    /// Note this runs unconditionally, including on tombstoned entities. See
    /// ``EntityRecord`` for why coupling the two register groups would break commutativity.
    private func mergeFields(_ base: EntityRecord, _ change: Change) -> MergeResult {
        var next = base
        var resolutions: [Resolution] = []
        resolutions.reserveCapacity(change.fields.count)

        // Sorted so the resolution list is deterministic across runs. Swift dictionaries
        // have no stable iteration order, and a merge whose *reported* decisions varied
        // run to run would make the conflict screen and the tests both flaky — even though
        // the resulting state is order-independent by construction.
        for name in change.fields.keys.sorted() {
            guard let value = change.fields[name] else { continue }
            let existing = base.clock(of: name)

            // Strictly-greater, not greater-or-equal. Equality means the very same write
            // arriving twice, and re-applying it must be a no-op for idempotence. Because
            // Hlc's order is total and includes nodeId, equal clocks imply equal writes.
            if existing == nil || change.hlc.isAfter(existing!) {
                next = next.withField(name, value, clock: change.hlc)
                resolutions.append(
                    .field(name, .applied, incoming: change.hlc, existing: existing))
            } else {
                resolutions.append(
                    .field(name, .superseded, incoming: change.hlc, existing: existing))
            }
        }

        return MergeResult(state: next, resolutions: resolutions)
    }

    /// The lifecycle register is itself last-writer-wins: whichever of DELETE and RESTORE
    /// carries the higher HLC takes effect.
    ///
    /// This is what makes deletion terminal without making it irreversible. A tombstone is
    /// never cleared as a side effect of some later field edit arriving — only an explicit
    /// `RESTORE`, stamped later than the delete, brings an entity back. Users get deletes
    /// that stay deleted, and an undo that works.
    private func mergeLifecycle(
        _ base: EntityRecord, _ change: Change, deleting: Bool
    ) -> MergeResult {
        let existing = base.lifecycleClock

        if existing == nil || change.hlc.isAfter(existing!) {
            return MergeResult(
                state: base.withLifecycle(deleted: deleting, clock: change.hlc),
                resolutions: [.lifecycle(.applied, incoming: change.hlc, existing: existing)])
        }
        return MergeResult(
            state: base,
            resolutions: [.lifecycle(.superseded, incoming: change.hlc, existing: existing)])
    }

    /// Folds a batch of changes for a single entity into one state.
    ///
    /// Order-insensitive by construction, so callers need not sort the batch.
    public func mergeAll(_ current: EntityRecord?, _ changes: some Sequence<Change>) -> EntityRecord? {
        var state = current
        for change in changes {
            state = merge(state, change).state
        }
        return state
    }
}
