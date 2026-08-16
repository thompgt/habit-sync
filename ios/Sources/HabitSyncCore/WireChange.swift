import Foundation

/// A ``Change`` in its over-the-wire form: enums as strings, the HLC in its compact
/// encoding, field values as plain text.
///
/// Deliberately not the same type as ``Change``. The wire format is a compatibility surface
/// that outlives any single client version, and keeping it separate means an unknown enum
/// value from a newer peer is a validation error this client can report, rather than a
/// decoding crash. It also means ``Change`` stays free to gain type-safe structure without
/// breaking every deployed device.
///
/// ## Null field values are load-bearing
///
/// `fields` permits null *values*. A present key with a null value means "clear this
/// field"; an absent key means "this change does not touch it". Conflating the two turns
/// every clear operation into a silent no-op — see ``FieldValue``.
///
/// The `Codable` conformance below is written out rather than synthesised for exactly that
/// reason. Swift's default handling of optional values inside a dictionary is easy to get
/// subtly wrong (`dict[key] = nil` *removes* the key rather than storing a null), and the
/// failure is invisible: the request encodes, the server accepts it, and the user's cleared
/// field just never travels.
public struct WireChange: Hashable, Sendable, Codable {

    /// Client-generated, stable across retries; the basis of push idempotency.
    public let opId: UUID
    /// ``EntityType`` raw value.
    public let entityType: String
    /// Client-generated entity id.
    public let entityId: UUID
    /// ``OpKind`` raw value.
    public let kind: String
    /// ``Hlc/compactString`` form.
    public let hlc: String
    /// Field writes for `UPSERT`; empty otherwise. A present key with a nil value is a clear.
    public let fields: [String: String?]

    public init(
        opId: UUID,
        entityType: String,
        entityId: UUID,
        kind: String,
        hlc: String,
        fields: [String: String?]
    ) {
        self.opId = opId
        self.entityType = entityType
        self.entityId = entityId
        self.kind = kind
        self.hlc = hlc
        self.fields = fields
    }

    private enum Key: String, CodingKey {
        case opId, entityType, entityId, kind, hlc, fields
    }

    /// A coding key whose name is only known at runtime — one per field written.
    private struct FieldKey: CodingKey {
        let stringValue: String
        var intValue: Int? { nil }
        init(_ name: String) { stringValue = name }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { return nil }
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: Key.self)
        opId = try container.decode(UUID.self, forKey: .opId)
        entityType = try container.decode(String.self, forKey: .entityType)
        entityId = try container.decode(UUID.self, forKey: .entityId)
        kind = try container.decode(String.self, forKey: .kind)
        hlc = try container.decode(String.self, forKey: .hlc)

        // An absent map and an empty one mean the same thing, and a DELETE legitimately
        // carries neither.
        if container.contains(.fields), try !container.decodeNil(forKey: .fields) {
            let raw = try container.nestedContainer(keyedBy: FieldKey.self, forKey: .fields)
            var decoded: [String: String?] = [:]
            for key in raw.allKeys {
                if try raw.decodeNil(forKey: key) {
                    // updateValue, not subscript assignment: `decoded[k] = nil` deletes the
                    // key, which is exactly the clear-versus-untouched conflation this type
                    // exists to prevent.
                    decoded.updateValue(nil, forKey: key.stringValue)
                } else {
                    decoded.updateValue(try raw.decode(String.self, forKey: key), forKey: key.stringValue)
                }
            }
            fields = decoded
        } else {
            fields = [:]
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: Key.self)
        try container.encode(opId, forKey: .opId)
        try container.encode(entityType, forKey: .entityType)
        try container.encode(entityId, forKey: .entityId)
        try container.encode(kind, forKey: .kind)
        try container.encode(hlc, forKey: .hlc)

        var raw = container.nestedContainer(keyedBy: FieldKey.self, forKey: .fields)
        // Sorted purely so a request body is reproducible in a test or a packet capture;
        // JSON object order carries no meaning to either end.
        for name in fields.keys.sorted() {
            guard let value = fields[name] else { continue }
            if let value {
                try raw.encode(value, forKey: FieldKey(name))
            } else {
                try raw.encodeNil(forKey: FieldKey(name))
            }
        }
    }
}
