import Foundation

/// The kinds of entity the sync engine replicates.
///
/// Note there is no "append-only" flag here, and no special path for ``habitCompletion``
/// or ``workoutSet``. Append-only-ness is a property of how the application *uses* those
/// entities — it creates them once and never edits them — not a rule the merge engine
/// enforces. Uniform treatment means one merge path to reason about, and it leaves the
/// door open to editing a mis-logged set later without touching the engine.
///
/// The raw values are the JVM enum names, because they are what crosses the wire.
public enum EntityType: String, CaseIterable, Hashable, Sendable {
    /// A habit definition: name, weekly target, colour. Mutable.
    case habit = "HABIT"
    /// An exercise definition: name, muscle group. Mutable.
    case exercise = "EXERCISE"
    /// A record that a habit was completed at a point in time. Created once.
    case habitCompletion = "HABIT_COMPLETION"
    /// A workout session envelope: start and end times.
    case workoutSession = "WORKOUT_SESSION"
    /// One set within a session: exercise, reps, weight. Created once.
    case workoutSet = "WORKOUT_SET"
}

/// What a ``Change`` does to an entity.
///
/// There is deliberately no `CREATE`. Entity ids are generated client-side as UUIDs, so
/// "create" and "update" are the same operation — an ``upsert`` that happens to be the
/// first one seen. Collapsing them removes an entire class of conflict
/// (create-vs-update ordering) rather than resolving it.
public enum OpKind: String, CaseIterable, Hashable, Sendable {
    /// Write one or more field values. Creates the entity if it is not yet known.
    case upsert = "UPSERT"
    /// Tombstone the entity. See ADR-003.
    case delete = "DELETE"
    /// Clear a tombstone. Only ever produced by an explicit user "undo"; deletion is never
    /// undone as a side effect of clock ordering.
    case restore = "RESTORE"
}

/// Identifies an entity across types.
///
/// Entity ids are UUIDs and so are globally unique on their own, but pairing them with the
/// type keeps lookups typed and makes a wrong-table bug an assertion failure rather than a
/// silent miss.
public struct EntityKey: Hashable, Sendable, CustomStringConvertible {
    public let type: EntityType
    public let id: UUID

    public init(type: EntityType, id: UUID) {
        self.type = type
        self.id = id
    }

    public var description: String { "\(type.rawValue)/\(id.uuidString)" }
}

/// A single field's value, already serialised to text by the storage layer.
///
/// This wrapper exists to make "set this field to null" expressible. A bare
/// `[String: String]` cannot distinguish *"this change does not touch the field"* (key
/// absent) from *"this change clears the field"* (key present, value nil). Conflating the
/// two means a user clearing a habit's colour is silently treated as not having touched
/// it, and their edit never propagates.
///
/// The core stays deliberately type-agnostic: converting an `Int`, a `Date` or an enum to
/// and from this text form is the storage layer's job on each platform. The engine only
/// ever compares and copies these values, never interprets them.
///
/// One consequence worth stating: the text encoding must be **canonical**. Two devices
/// writing the same logical value must produce identical strings, or convergence checks
/// report false divergence. Store weights as integer grams, not floating-point kilograms —
/// and note that `Double.description` differs between Swift and Java, which is exactly the
/// sort of difference that would show up as two devices that never agree.
public struct FieldValue: Hashable, Sendable, CustomStringConvertible {
    /// The serialised value, or `nil` to represent SQL NULL.
    public let raw: String?

    public init(_ raw: String?) { self.raw = raw }

    /// The explicit "field is null" value.
    public static let null = FieldValue(nil)

    public static func of(_ raw: String?) -> FieldValue { FieldValue(raw) }

    public static func of(_ value: Int64) -> FieldValue { FieldValue(String(value)) }

    public static func of(_ value: Int) -> FieldValue { FieldValue(String(value)) }

    public static func of(_ value: Bool) -> FieldValue { FieldValue(value ? "true" : "false") }

    /// Milliseconds since the epoch — the encoding the JVM client writes for `at` and the
    /// workout timestamps, kept here so no caller reinvents it with a different format.
    public static func of(_ value: Date) -> FieldValue {
        FieldValue(String(Int64((value.timeIntervalSince1970 * 1000).rounded())))
    }

    public static func of(_ value: UUID) -> FieldValue {
        // Lowercase, because Foundation's uuidString is uppercase and Java's
        // UUID.toString() is lowercase. Two devices writing the same id must produce the
        // same bytes or every comparison between them silently fails.
        FieldValue(value.uuidString.lowercased())
    }

    public var isNull: Bool { raw == nil }

    public var description: String { raw ?? "<null>" }
}
