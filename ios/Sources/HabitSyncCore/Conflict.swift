import Foundation

/// A report that merging a pulled page discarded or hid somebody's work.
///
/// ADR-001 accepts that last-writer-wins loses data when two devices edit one field
/// concurrently. ADR-003 accepts that a tombstone hides a concurrent edit. Both acceptances
/// are conditional, and the condition is the same: **the loss is shown to the user rather
/// than swallowed.** Silent data loss is a bug; visible, explained loss under a documented
/// rule is a trade-off. This type is what makes that difference deliverable — without it
/// the merge engine's verdicts die inside ``SyncEngine`` and no screen can ever mention
/// them.
///
/// ## Whose loss it was
///
/// ``lostLocalWrite`` separates the write the user typed on *this* device from one that
/// some other replica lost. Only the former is worth interrupting anybody about; the rest
/// is debug-screen material. Provenance is exact rather than guessed — an ``Hlc`` carries
/// the id of the node that stamped it, so a value written here is distinguishable from one
/// that merely arrived here earlier and has sat in the register since.
public struct Conflict: Hashable, Sendable, Identifiable, CustomStringConvertible {

    /// The three ways a merge costs somebody work.
    ///
    /// They are distinguished because they read differently to a user, not because the
    /// engine treats them differently. "Your rename was overwritten" and "the habit you
    /// were renaming no longer exists" call for different wording, and a client that could
    /// only say "conflict" would be useless.
    public enum Kind: Hashable, Sendable {
        /// Two writes reached one field register; the older one was dropped.
        case fieldOverwritten
        /// A DELETE and a RESTORE contended for the lifecycle register.
        case lifecycleContested
        /// A delete from another device hid an entity holding writes this device authored.
        ///
        /// The scenario ADR-003 opens with: one device deletes "Evening Run" while another
        /// renames it. Note there is no *register* contest here — the lifecycle register
        /// was unset, so the delete applied unopposed, and the field writes survive
        /// untouched in their own registers. The loss is one of *visibility*, which is
        /// exactly why it needs reporting separately: the merge engine, correctly, saw
        /// nothing worth remarking on.
        case hiddenByDelete
    }

    public let entity: EntityKey
    public let kind: Kind
    /// The contested field, or `nil` when the contest was not about one.
    public let field: String?
    /// The clock of the write that stands.
    public let winner: Hlc
    /// The clock of the write that was discarded or hidden.
    public let loser: Hlc
    /// The losing write was stamped by this device.
    public let lostLocalWrite: Bool

    /// Stable within a sync outcome, so SwiftUI lists do not reshuffle on redraw. The loser
    /// clock is unique per lost write, and the entity disambiguates the rest.
    public var id: String { "\(entity)|\(field ?? "-")|\(loser.compactString)" }

    init(entity: EntityKey, kind: Kind, field: String?, winner: Hlc, loser: Hlc, lostLocalWrite: Bool) {
        precondition(
            kind != .fieldOverwritten || field != nil, "A field conflict must name its field")
        precondition(
            kind == .fieldOverwritten || field == nil, "Only a field conflict may name a field")
        self.entity = entity
        self.kind = kind
        self.field = field
        self.winner = winner
        self.loser = loser
        self.lostLocalWrite = lostLocalWrite
    }

    /// Builds a report from a merge decision, or `nil` when nothing was displaced.
    ///
    /// A write into an unset register is the ordinary case, not a conflict. Reporting those
    /// would bury the real ones under every first write the device ever pulls.
    ///
    /// - Parameter nodeId: this device's node id, which decides ``lostLocalWrite``.
    static func from(entity: EntityKey, resolution: Resolution, nodeId: String) -> Conflict? {
        guard let existing = resolution.existing else { return nil }
        if existing == resolution.incoming {
            // The same write arriving a second time — a duplicated page, or this device
            // pulling back an op it pushed a moment ago. Merge treats that as idempotence
            // and so must this: an at-least-once network would otherwise manufacture a
            // "conflict" notice out of every successful round trip.
            return nil
        }
        let incomingLost = resolution.lostConflict
        if incomingLost && resolution.incoming.nodeId == nodeId {
            // An op this device stamped, coming back down the pull stream and losing to a
            // write that already displaced it. The loss was reported when the winner
            // arrived; reporting it again on the echo would double-count every contested
            // field, because the server's log carries our own ops back to us.
            return nil
        }
        let winner = incomingLost ? existing : resolution.incoming
        let loser = incomingLost ? resolution.incoming : existing
        let kind: Kind = resolution.target == .field ? .fieldOverwritten : .lifecycleContested
        return Conflict(
            entity: entity,
            kind: kind,
            field: resolution.field,
            winner: winner,
            loser: loser,
            lostLocalWrite: loser.nodeId == nodeId)
    }

    /// Reports that `deletedBy` hid an entity carrying this device's own writes.
    ///
    /// - Parameter hiddenWrite: the newest field write this device authored on the entity.
    static func hiddenByDelete(entity: EntityKey, deletedBy: Hlc, hiddenWrite: Hlc) -> Conflict {
        Conflict(
            entity: entity, kind: .hiddenByDelete, field: nil,
            winner: deletedBy, loser: hiddenWrite, lostLocalWrite: true)
    }

    public var description: String {
        switch kind {
        case .fieldOverwritten:
            return "\(entity) field '\(field ?? "?")' overwritten by \(winner), losing \(loser)"
        case .lifecycleContested:
            return "\(entity) lifecycle decided by \(winner), losing \(loser)"
        case .hiddenByDelete:
            return "\(entity) hidden by a delete at \(winner), hiding your write \(loser)"
        }
    }
}
