import XCTest

@testable import HabitSyncCore

/// Helpers shared by the merge tests. Kept explicit rather than random so a failure names a
/// scenario rather than a seed.
enum Fixture {
    static let habitId = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!

    static func hlc(_ millis: Int64, _ logical: Int = 0, _ node: String = "a") -> Hlc {
        Hlc(physicalMillis: millis, logical: logical, nodeId: node)
    }

    static func upsert(
        _ fields: [String: FieldValue], at hlc: Hlc, id: UUID = Fixture.habitId
    ) -> Change {
        Change.upsert(type: .habit, entityId: id, hlc: hlc, fields: fields)
    }

    static func delete(at hlc: Hlc, id: UUID = Fixture.habitId) -> Change {
        Change.delete(type: .habit, entityId: id, hlc: hlc)
    }

    static func restore(at hlc: Hlc, id: UUID = Fixture.habitId) -> Change {
        Change.restore(type: .habit, entityId: id, hlc: hlc)
    }
}

final class MergeEngineTests: XCTestCase {

    private let engine = MergeEngine()

    func testFirstWriteCreatesTheEntity() {
        let result = engine.merge(nil, Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(1)))

        XCTAssertEqual(result.state.field("name"), FieldValue("Run"))
        XCTAssertTrue(result.mutated)
        XCTAssertTrue(result.state.visible)
    }

    /// The single most valuable property of this design: two devices editing *different*
    /// fields both keep their edit, where per-row LWW would discard one.
    func testDisjointFieldWritesBothSurvive() {
        var state = engine.merge(nil, Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(1, 0, "a"))).state
        state = engine.merge(state, Fixture.upsert(["colour": .of("red")], at: Fixture.hlc(2, 0, "b"))).state

        XCTAssertEqual(state.field("name"), FieldValue("Run"))
        XCTAssertEqual(state.field("colour"), FieldValue("red"))
    }

    func testTheNewerWriteWinsTheSameField() {
        var state = engine.merge(nil, Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(1))).state
        let result = engine.merge(state, Fixture.upsert(["name": .of("Jog")], at: Fixture.hlc(2)))
        state = result.state

        XCTAssertEqual(state.field("name"), FieldValue("Jog"))
        XCTAssertEqual(result.superseded.count, 0)
    }

    func testAnOlderWriteIsDroppedAndReported() {
        let state = engine.merge(nil, Fixture.upsert(["name": .of("Jog")], at: Fixture.hlc(5))).state
        let result = engine.merge(state, Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(2)))

        XCTAssertEqual(result.state.field("name"), FieldValue("Jog"))
        XCTAssertFalse(result.mutated)
        XCTAssertEqual(result.superseded.count, 1)
        XCTAssertEqual(result.superseded.first?.field, "name")
    }

    /// Equal clocks mean the same write arriving twice. Re-applying must be a no-op, or a
    /// duplicated page double-counts.
    func testReapplyingTheSameChangeChangesNothing() {
        let change = Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(1))
        let once = engine.merge(nil, change).state
        let twice = engine.merge(once, change)

        XCTAssertEqual(twice.state, once)
        XCTAssertFalse(twice.mutated)
    }

    /// Clearing a field is a write, not an absence. The value must land as an explicit null
    /// that other replicas will adopt.
    func testClearingAFieldIsAWrite() {
        var state = engine.merge(nil, Fixture.upsert(["colour": .of("red")], at: Fixture.hlc(1))).state
        state = engine.merge(state, Fixture.upsert(["colour": .null], at: Fixture.hlc(2))).state

        XCTAssertNotNil(state.field("colour"), "the register must exist")
        XCTAssertTrue(state.field("colour")?.isNull ?? false)
    }

    func testDeleteHidesTheEntityWithoutTouchingItsFields() {
        var state = engine.merge(nil, Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(1))).state
        state = engine.merge(state, Fixture.delete(at: Fixture.hlc(2))).state

        XCTAssertFalse(state.visible)
        // The fields survive untouched — which is what makes a restore bring the habit back
        // intact, and what keeps merge commutative.
        XCTAssertEqual(state.field("name"), FieldValue("Run"))
    }

    /// The counterexample in EntityRecord's documentation, asserted: couple the two register
    /// groups and these two orders produce different field values.
    func testDeleteAndUpsertCommute() {
        let upsert = Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(5))
        let delete = Fixture.delete(at: Fixture.hlc(3))

        let oneWay = engine.merge(engine.merge(nil, upsert).state, delete).state
        let otherWay = engine.merge(engine.merge(nil, delete).state, upsert).state

        XCTAssertEqual(oneWay, otherWay)
        XCTAssertFalse(oneWay.visible)
        XCTAssertEqual(oneWay.field("name"), FieldValue("Run"))
    }

    func testRestoreOnlyWinsWhenItIsNewerThanTheDelete() {
        let deleted = engine.merge(nil, Fixture.delete(at: Fixture.hlc(5))).state

        let staleRestore = engine.merge(deleted, Fixture.restore(at: Fixture.hlc(2))).state
        XCTAssertFalse(staleRestore.visible, "an older restore must not resurrect")

        let freshRestore = engine.merge(deleted, Fixture.restore(at: Fixture.hlc(9))).state
        XCTAssertTrue(freshRestore.visible)
    }

    /// A tombstone is never lifted as a side effect of a later field edit arriving. That is
    /// what makes deletion terminal without making it irreversible.
    func testALaterFieldWriteDoesNotResurrect() {
        var state = engine.merge(nil, Fixture.delete(at: Fixture.hlc(5))).state
        state = engine.merge(state, Fixture.upsert(["name": .of("Run")], at: Fixture.hlc(50))).state

        XCTAssertFalse(state.visible)
    }
}
