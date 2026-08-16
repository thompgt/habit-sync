import HabitSyncCore
import XCTest

@testable import HabitSyncClient

/// The durable store's contract: what it must round-trip, and what it must keep together.
///
/// In-memory databases here, so these test atomicity and encoding rather than durability —
/// surviving power loss is what `synchronous=FULL` is for and is not assertable from a unit
/// test. What *is* assertable is that nothing is dropped or conflated on the way to disk.
final class SQLiteLocalStoreTests: XCTestCase {

    private var store: SQLiteLocalStore!
    private let key = EntityKey(type: .habit, id: UUID())

    override func setUpWithError() throws {
        try super.setUpWithError()
        store = try SQLiteLocalStore.inMemory()
    }

    private func hlc(_ millis: Int64, _ node: String = "device-a") -> Hlc {
        Hlc(physicalMillis: millis, logical: 0, nodeId: node)
    }

    private func record(_ fields: [String: FieldValue], at clock: Hlc, deleted: Bool = false) -> EntityRecord {
        EntityRecord(
            type: key.type, id: key.id,
            fields: fields,
            fieldClocks: fields.mapValues { _ in clock },
            deleted: deleted,
            lifecycleClock: deleted ? clock : nil)
    }

    func testAnUnknownEntityLoadsAsNothing() throws {
        XCTAssertNil(try store.load(key))
    }

    func testApplyLocalWritesTheEntityAndTheOutboxTogether() throws {
        let clock = hlc(1_000)
        let op = Change.upsert(type: .habit, entityId: key.id, hlc: clock, fields: ["name": .of("Run")])
        try store.applyLocal(merged: record(["name": .of("Run")], at: clock), op: op)

        XCTAssertEqual(try store.load(key)?.field("name"), FieldValue("Run"))
        XCTAssertEqual(try store.pendingOpCount(), 1)
        XCTAssertEqual(try store.pendingOps(limit: 10).first?.opId, op.opId)
        // The clock is derived from the op in the same commit — never saved separately, or it
        // could land on the other side of a crash from the change that used it.
        XCTAssertEqual(try store.lastClock(), clock)
    }

    /// The distinction the whole design hangs on: a cleared field is a row with a NULL value,
    /// not a missing row.
    func testAClearedFieldIsStoredAndReadBackAsAClear() throws {
        let clock = hlc(1_000)
        try store.applyLocal(
            merged: record(["colour": .null], at: clock),
            op: Change.upsert(type: .habit, entityId: key.id, hlc: clock, fields: ["colour": .null]))

        let loaded = try XCTUnwrap(try store.load(key))
        XCTAssertNotNil(loaded.field("colour"), "the register must exist")
        XCTAssertTrue(loaded.field("colour")?.isNull ?? false)
        XCTAssertNil(loaded.field("name"), "a field never written must stay absent")
    }

    /// The op payload goes through the same codec the wire uses, so a clear that survives here
    /// is a clear that survives the network.
    func testAnOutboxOpRoundTripsThroughItsStoredPayload() throws {
        let clock = hlc(1_000)
        let op = Change.upsert(
            type: .habit, entityId: key.id, hlc: clock, fields: ["colour": .null, "name": .of("Run")])
        try store.applyLocal(merged: record(["colour": .null, "name": .of("Run")], at: clock), op: op)

        let restored = try XCTUnwrap(try store.pendingOps(limit: 10).first)
        XCTAssertEqual(restored, op)
    }

    func testTheOutboxIsOldestFirstAndAcknowledgingRemovesOnlyWhatWasNamed() throws {
        var ops: [Change] = []
        for index in 1...3 {
            let clock = hlc(Int64(1_000 + index))
            let op = Change.upsert(
                type: .habit, entityId: key.id, hlc: clock, fields: ["name": .of("v\(index)")])
            try store.applyLocal(merged: record(["name": .of("v\(index)")], at: clock), op: op)
            ops.append(op)
        }

        XCTAssertEqual(try store.pendingOps(limit: 10).map(\.opId), ops.map(\.opId))

        try store.acknowledgeOps([ops[1].opId, UUID()])
        XCTAssertEqual(try store.pendingOps(limit: 10).map(\.opId), [ops[0].opId, ops[2].opId])
    }

    func testApplyRemoteMovesTheWatermarkWithTheState() throws {
        let clock = hlc(2_000, "device-b")
        try store.applyRemote(merged: [record(["name": .of("Jog")], at: clock)], nextSeq: 7, clock: clock)

        XCTAssertEqual(try store.watermark(), 7)
        XCTAssertEqual(try store.load(key)?.field("name"), FieldValue("Jog"))
        XCTAssertEqual(try store.lastClock(), clock)
    }

    /// A watermark that went backwards would re-offer changes the device already applied —
    /// harmless — but one that went backwards *silently* usually means a bug upstream, and it
    /// is cheap to refuse.
    func testTheWatermarkRefusesToGoBackwards() throws {
        let clock = hlc(2_000)
        try store.applyRemote(merged: [], nextSeq: 7, clock: clock)
        XCTAssertThrowsError(try store.applyRemote(merged: [], nextSeq: 6, clock: clock))
    }

    /// `lastClock` is a high-water mark, not the most recent write. A page carrying an older
    /// clock must not drag it back.
    func testTheStoredClockOnlyEverMovesForward() throws {
        try store.applyRemote(merged: [], nextSeq: 1, clock: hlc(5_000))
        try store.applyRemote(merged: [], nextSeq: 2, clock: hlc(1_000))
        XCTAssertEqual(try store.lastClock(), hlc(5_000))
    }

    /// ADR-003's recovery path. Entity state goes; the outbox and the clock do not — those ops
    /// are the device's own un-pushed work, and the clock is its monotonic identity.
    func testResyncClearsStateButKeepsTheOutboxAndTheClock() throws {
        let clock = hlc(1_000)
        let op = Change.upsert(type: .habit, entityId: key.id, hlc: clock, fields: ["name": .of("Run")])
        try store.applyLocal(merged: record(["name": .of("Run")], at: clock), op: op)
        try store.applyRemote(merged: [], nextSeq: 12, clock: clock)

        try store.resetForResync()

        XCTAssertNil(try store.load(key))
        XCTAssertEqual(try store.watermark(), 0)
        XCTAssertEqual(try store.pendingOpCount(), 1, "un-pushed work must survive")
        XCTAssertEqual(try store.lastClock(), clock, "identity must survive")
    }

    func testEnqueueingTheSameOpTwiceKeepsOneCopy() throws {
        let clock = hlc(1_000)
        let op = Change.upsert(type: .habit, entityId: key.id, hlc: clock, fields: ["name": .of("Run")])
        let merged = record(["name": .of("Run")], at: clock)

        try store.applyLocal(merged: merged, op: op)
        try store.applyLocal(merged: merged, op: op)

        XCTAssertEqual(try store.pendingOpCount(), 1)
    }

    func testRecordsCanBeListedByType() throws {
        let clock = hlc(1_000)
        try store.applyRemote(merged: [record(["name": .of("Run")], at: clock)], nextSeq: 1, clock: clock)

        let completionId = UUID()
        let completion = EntityRecord(
            type: .habitCompletion, id: completionId,
            fields: ["habitId": .of(key.id)], fieldClocks: ["habitId": clock],
            deleted: false, lifecycleClock: nil)
        try store.applyRemote(merged: [completion], nextSeq: 2, clock: clock)

        XCTAssertEqual(try store.records(ofType: .habit).map(\.id), [key.id])
        XCTAssertEqual(try store.records(ofType: .habitCompletion).map(\.id), [completionId])
        XCTAssertEqual(try store.allRecords().count, 2)
    }

    /// A tombstone is a record the store still holds — that is how a delete reaches a device
    /// that was offline when it happened.
    func testTombstonesAreHeldButNotVisible() throws {
        let clock = hlc(3_000)
        try store.applyRemote(
            merged: [record(["name": .of("Run")], at: clock, deleted: true)], nextSeq: 1, clock: clock)

        XCTAssertEqual(try store.allRecords().count, 1)
        XCTAssertTrue(try store.visibleRecords(ofType: .habit).isEmpty)
        XCTAssertEqual(try store.load(key)?.field("name"), FieldValue("Run"))
    }
}
