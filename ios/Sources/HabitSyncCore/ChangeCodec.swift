import Foundation

/// Translates between ``Change`` and ``WireChange`` — the one place the protocol's encoding
/// is defined on this platform.
///
/// The JVM has the matching half in `sync-core`'s `ChangeCodec`, and the two must accept
/// and reject exactly the same payloads. When each side owned its own translation there was
/// nothing stopping them disagreeing about, say, whether an absent `fields` map meant
/// "empty" or "invalid", and that class of bug shows up as data quietly not arriving.
///
/// Every rejection is a ``CodecError`` naming the offending value. A change this client
/// cannot decode is permanently undeliverable, not temporarily: the same bytes will fail
/// identically forever, so the watermark must not advance past it and the sync must not be
/// retried in a loop.
public enum ChangeCodec {

    /// Encodes a change for transmission.
    public static func encode(_ change: Change) -> WireChange {
        var fields: [String: String?] = [:]
        for (name, value) in change.fields {
            // updateValue rather than subscript assignment: storing a nil through the
            // subscript would delete the key, turning "clear this field" into "this change
            // does not touch it" — a silent no-op on the server.
            fields.updateValue(value.raw, forKey: name)
        }

        return WireChange(
            opId: change.opId,
            entityType: change.entityType.rawValue,
            entityId: change.entityId,
            kind: change.kind.rawValue,
            hlc: change.hlc.compactString,
            fields: fields)
    }

    /// Decodes a received change, validating it.
    ///
    /// - Throws: ``CodecError`` if any component is missing or unrecognised. An unknown
    ///   entity type or op kind from a newer peer is a protocol mismatch this client can act
    ///   on by upgrading — not something to guess at.
    public static func decode(_ wire: WireChange) throws -> Change {
        guard let type = EntityType(rawValue: wire.entityType) else {
            throw CodecError("Unknown entityType on op \(wire.opId): \(wire.entityType)")
        }
        guard let kind = OpKind(rawValue: wire.kind) else {
            throw CodecError("Unknown kind on op \(wire.opId): \(wire.kind)")
        }
        guard !wire.hlc.isEmpty else {
            throw CodecError("Op \(wire.opId) is missing an hlc")
        }
        let hlc: Hlc
        do {
            hlc = try Hlc.parse(wire.hlc)
        } catch {
            throw CodecError("Malformed hlc on op \(wire.opId): \(wire.hlc)")
        }

        var fields: [String: FieldValue] = [:]
        for (name, value) in wire.fields {
            fields[name] = FieldValue(value)
        }

        // The invariant that DELETE and RESTORE carry no field writes is enforced by
        // Change's initialiser on the JVM, where it is a rejected request. Here it must be
        // checked rather than asserted: this input came off the network, and tripping a
        // precondition would crash the app on a malformed page instead of reporting it.
        guard kind == .upsert || fields.isEmpty else {
            throw CodecError(
                "\(kind.rawValue) op \(wire.opId) must not carry field writes, got: \(fields.keys.sorted())")
        }

        return Change(
            opId: wire.opId,
            entityType: type,
            entityId: wire.entityId,
            kind: kind,
            hlc: hlc,
            fields: fields)
    }
}
