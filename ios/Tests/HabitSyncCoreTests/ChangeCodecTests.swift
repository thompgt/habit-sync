import XCTest

@testable import HabitSyncCore

/// The wire format is a compatibility surface shared with a JVM server and a JVM client, so
/// these assert the bytes, not just the round trip.
final class ChangeCodecTests: XCTestCase {

    func testRoundTripsAnUpsert() throws {
        let change = Fixture.upsert(
            ["name": .of("Run"), "colour": .null], at: Fixture.hlc(1_234, 5, "device-a"))

        let decoded = try ChangeCodec.decode(ChangeCodec.encode(change))

        XCTAssertEqual(decoded, change)
        XCTAssertTrue(decoded.fields["colour"]?.isNull ?? false)
    }

    /// The distinction the whole `FieldValue` type exists for. A cleared field must survive
    /// JSON as a present key with a null value; drop it and the user's clear silently becomes
    /// "this change does not touch the field".
    func testAClearedFieldSurvivesJsonAsAnExplicitNull() throws {
        let change = Fixture.upsert(["colour": .null], at: Fixture.hlc(1))

        let json = try JSONEncoder().encode(ChangeCodec.encode(change))
        let text = String(data: json, encoding: .utf8) ?? ""
        XCTAssertTrue(text.contains("\"colour\":null"), "expected an explicit null, got: \(text)")

        let decoded = try ChangeCodec.decode(try JSONDecoder().decode(WireChange.self, from: json))
        XCTAssertEqual(decoded.fields.count, 1)
        XCTAssertTrue(decoded.fields["colour"]?.isNull ?? false)
    }

    /// A cleared field and an untouched one must not decode to the same thing.
    func testAnAbsentFieldIsNotAClearedOne() throws {
        let cleared = try ChangeCodec.decode(
            try JSONDecoder().decode(
                WireChange.self,
                from: Data(
                    #"{"opId":"11111111-1111-1111-1111-111111111111","entityType":"HABIT","entityId":"22222222-2222-2222-2222-222222222222","kind":"UPSERT","hlc":"1:0:a","fields":{"colour":null}}"#
                        .utf8)))
        let untouched = try ChangeCodec.decode(
            try JSONDecoder().decode(
                WireChange.self,
                from: Data(
                    #"{"opId":"11111111-1111-1111-1111-111111111111","entityType":"HABIT","entityId":"22222222-2222-2222-2222-222222222222","kind":"UPSERT","hlc":"1:0:a","fields":{}}"#
                        .utf8)))

        XCTAssertEqual(cleared.fields.count, 1)
        XCTAssertEqual(untouched.fields.count, 0)
    }

    /// The JVM sends enum names and an HLC compact string. Anything else here would be a
    /// protocol this client can talk but nothing else can hear.
    func testEncodesTheJvmsSpelling() throws {
        let change = Change.delete(type: .habitCompletion, entityId: Fixture.habitId, hlc: Fixture.hlc(7, 1, "n"))
        let wire = ChangeCodec.encode(change)

        XCTAssertEqual(wire.entityType, "HABIT_COMPLETION")
        XCTAssertEqual(wire.kind, "DELETE")
        XCTAssertEqual(wire.hlc, "7:1:n")
        XCTAssertTrue(wire.fields.isEmpty)
    }

    /// An unknown value from a newer peer is a rejected change, not a crash and not a guess.
    func testRejectsUnknownEnumValues() {
        let unknownType = WireChange(
            opId: UUID(), entityType: "TIME_MACHINE", entityId: UUID(), kind: "UPSERT",
            hlc: "1:0:a", fields: [:])
        XCTAssertThrowsError(try ChangeCodec.decode(unknownType))

        let unknownKind = WireChange(
            opId: UUID(), entityType: "HABIT", entityId: UUID(), kind: "ANNIHILATE",
            hlc: "1:0:a", fields: [:])
        XCTAssertThrowsError(try ChangeCodec.decode(unknownKind))
    }

    func testRejectsAMalformedClock() {
        let wire = WireChange(
            opId: UUID(), entityType: "HABIT", entityId: UUID(), kind: "UPSERT",
            hlc: "not-a-clock", fields: ["name": "Run"])
        XCTAssertThrowsError(try ChangeCodec.decode(wire))
    }

    /// Decoding is the untrusted path, so this invariant is checked rather than asserted — a
    /// malformed page must be reported, not crash the app.
    func testRejectsADeleteCarryingFieldWrites() {
        let wire = WireChange(
            opId: UUID(), entityType: "HABIT", entityId: UUID(), kind: "DELETE",
            hlc: "1:0:a", fields: ["name": "Run"])
        XCTAssertThrowsError(try ChangeCodec.decode(wire))
    }

    /// A DELETE from the server legitimately arrives with no `fields` key at all.
    func testToleratesAnAbsentFieldsMap() throws {
        let json = Data(
            #"{"opId":"11111111-1111-1111-1111-111111111111","entityType":"HABIT","entityId":"22222222-2222-2222-2222-222222222222","kind":"DELETE","hlc":"1:0:a"}"#
                .utf8)
        let decoded = try ChangeCodec.decode(try JSONDecoder().decode(WireChange.self, from: json))
        XCTAssertEqual(decoded.kind, .delete)
        XCTAssertTrue(decoded.fields.isEmpty)
    }
}
