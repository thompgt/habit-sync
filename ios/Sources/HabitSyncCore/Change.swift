import Foundation

/// One mutation to one entity — the atom that flows over the wire and through
/// ``MergeEngine``.
///
/// A change is immutable and self-describing: everything needed to merge it is present, so
/// it can be applied on any replica, in any order, any number of times.
public struct Change: Hashable, Sendable {

    /// Client-generated identifier, stable across retries.
    ///
    /// This is what makes push idempotent: a request that times out after the server
    /// committed is safe to replay, because the server recognises the op id and treats the
    /// replay as a no-op instead of double-applying it.
    public let opId: UUID

    /// Which table this targets.
    public let entityType: EntityType

    /// Client-generated entity id.
    public let entityId: UUID

    /// What the change does.
    public let kind: OpKind

    /// The originating device's clock reading, and the sole basis for conflict resolution.
    public let hlc: Hlc

    /// Field writes for ``OpKind/upsert``; always empty for `delete` and `restore`.
    public let fields: [String: FieldValue]

    public init(
        opId: UUID,
        entityType: EntityType,
        entityId: UUID,
        kind: OpKind,
        hlc: Hlc,
        fields: [String: FieldValue]
    ) {
        precondition(
            kind == .upsert || fields.isEmpty,
            "\(kind.rawValue) changes must not carry field writes, got: \(fields.keys.sorted())")
        self.opId = opId
        self.entityType = entityType
        self.entityId = entityId
        self.kind = kind
        self.hlc = hlc
        self.fields = fields
    }

    public static func upsert(
        opId: UUID = UUID(),
        type: EntityType,
        entityId: UUID,
        hlc: Hlc,
        fields: [String: FieldValue]
    ) -> Change {
        Change(opId: opId, entityType: type, entityId: entityId, kind: .upsert, hlc: hlc, fields: fields)
    }

    public static func delete(
        opId: UUID = UUID(), type: EntityType, entityId: UUID, hlc: Hlc
    ) -> Change {
        Change(opId: opId, entityType: type, entityId: entityId, kind: .delete, hlc: hlc, fields: [:])
    }

    public static func restore(
        opId: UUID = UUID(), type: EntityType, entityId: UUID, hlc: Hlc
    ) -> Change {
        Change(opId: opId, entityType: type, entityId: entityId, kind: .restore, hlc: hlc, fields: [:])
    }

    /// Identifies the entity this change targets, for grouping and lookup.
    public var key: EntityKey { EntityKey(type: entityType, id: entityId) }
}

/// A change paired with the sequence number the server assigned it.
///
/// The sequence is the server's, not the originating device's: it is the position in the
/// per-user replication log, allocated under a row lock at commit time (ADR-002). It has
/// nothing to do with the change's ``Hlc`` and must never be used to order a merge — two
/// changes can arrive in sequence order 7, 8 while their HLCs say the opposite, and the
/// HLC is the one that decides who wins.
///
/// Its only job is to be a resumable cursor: "give me everything after 42".
public struct SequencedChange: Hashable, Sendable {
    /// Position in the user's replication log, strictly increasing.
    public let serverSeq: Int64
    public let change: Change

    public init(serverSeq: Int64, change: Change) {
        precondition(serverSeq >= 1, "serverSeq must be >= 1, got \(serverSeq)")
        self.serverSeq = serverSeq
        self.change = change
    }
}
