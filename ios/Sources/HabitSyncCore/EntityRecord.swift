import Foundation

/// The merged state of one entity: its field values, the clock reading that last wrote
/// each field, and its lifecycle (deleted or not).
///
/// **The two register groups are orthogonal, and that is not an accident.** Field writes
/// are applied regardless of whether the entity is deleted, and the tombstone is applied
/// regardless of what the fields say. Deletion affects *visibility at read time* — see
/// ``visible`` — not the field registers.
///
/// Coupling them breaks convergence. Suppose `UPSERT(name=X, hlc=5)` and `DELETE(hlc=3)`
/// are merged in different orders by two replicas, and suppose the engine discarded field
/// writes on tombstoned entities:
///
/// ```text
///   Replica A:  UPSERT then DELETE  ->  deleted, name = X
///   Replica B:  DELETE then UPSERT  ->  deleted, name = <unset>   // discarded
/// ```
///
/// The replicas now disagree. Keeping the groups independent makes merge commutative, so
/// both land on `deleted, name = X` and simply do not show it to the user.
///
/// Instances are immutable; every merge produces a new record.
public struct EntityRecord: Hashable, Sendable {

    public let type: EntityType
    public let id: UUID
    /// Current field values.
    public let fields: [String: FieldValue]
    /// HLC that last wrote each field; keys always match ``fields``.
    public let fieldClocks: [String: Hlc]
    /// Whether a tombstone is currently in force.
    public let deleted: Bool
    /// HLC of the last `DELETE` or `RESTORE`, or `nil` if the entity has never been either.
    public let lifecycleClock: Hlc?

    public init(
        type: EntityType,
        id: UUID,
        fields: [String: FieldValue],
        fieldClocks: [String: Hlc],
        deleted: Bool,
        lifecycleClock: Hlc?
    ) {
        precondition(
            Set(fields.keys) == Set(fieldClocks.keys),
            "Every field must carry exactly one clock; fields=\(fields.keys.sorted()) clocks=\(fieldClocks.keys.sorted())")
        precondition(
            !deleted || lifecycleClock != nil, "A deleted record must carry a lifecycleClock")
        self.type = type
        self.id = id
        self.fields = fields
        self.fieldClocks = fieldClocks
        self.deleted = deleted
        self.lifecycleClock = lifecycleClock
    }

    /// An entity with no fields written and no tombstone.
    public static func empty(type: EntityType, id: UUID) -> EntityRecord {
        EntityRecord(type: type, id: id, fields: [:], fieldClocks: [:], deleted: false, lifecycleClock: nil)
    }

    public static func empty(key: EntityKey) -> EntityRecord {
        empty(type: key.type, id: key.id)
    }

    public var key: EntityKey { EntityKey(type: type, id: id) }

    /// Whether the application should show this entity.
    ///
    /// The only place deletion is allowed to influence behaviour. Queries filter on this;
    /// merge never does.
    public var visible: Bool { !deleted }

    /// The value of `field`, or `nil` if it has never been written.
    ///
    /// Note the double optional at the call site is meaningful: `nil` means unwritten,
    /// `FieldValue.null` means written and cleared.
    public func field(_ name: String) -> FieldValue? { fields[name] }

    /// The clock that last wrote `field`, or `nil` if it was never written.
    public func clock(of name: String) -> Hlc? { fieldClocks[name] }

    /// A copy with `field` set to `value` as of `clock`.
    func withField(_ name: String, _ value: FieldValue, clock: Hlc) -> EntityRecord {
        var nextFields = fields
        var nextClocks = fieldClocks
        nextFields[name] = value
        nextClocks[name] = clock
        return EntityRecord(
            type: type, id: id, fields: nextFields, fieldClocks: nextClocks,
            deleted: deleted, lifecycleClock: lifecycleClock)
    }

    /// A copy with the lifecycle register set.
    func withLifecycle(deleted nowDeleted: Bool, clock: Hlc) -> EntityRecord {
        EntityRecord(
            type: type, id: id, fields: fields, fieldClocks: fieldClocks,
            deleted: nowDeleted, lifecycleClock: clock)
    }
}
