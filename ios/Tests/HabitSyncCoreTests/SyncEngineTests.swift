import XCTest

@testable import HabitSyncCore

/// The round-trip loop against a stand-in server, including the failure paths that are the
/// whole reason the protocol looks the way it does.
final class SyncEngineTests: XCTestCase {

    private var server: FakeServer!
    private var store: InMemoryLocalStore!
    private var time: FakeClock!
    private var engine: SyncEngine!

    private let us = "device-a"
    private let them = "device-b"

    override func setUp() {
        super.setUp()
        server = FakeServer()
        store = InMemoryLocalStore()
        time = FakeClock(1_000)
        engine = SyncEngine(
            clock: HlcClock(nodeId: us, timeSource: time), store: store, transport: server)
    }

    /// A change as if it came from the other device.
    private func fromThem(_ fields: [String: FieldValue], at millis: Int64) -> Change {
        Fixture.upsert(fields, at: Hlc(physicalMillis: millis, logical: 0, nodeId: them))
    }

    // MARK: - The ordinary loop

    func testPushClearsTheOutboxOnlyWhenTheServerConfirms() async throws {
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))
        XCTAssertEqual(try store.pendingOpCount(), 1)

        let outcome = try await engine.sync()

        XCTAssertEqual(outcome.opsAcknowledged, 1)
        XCTAssertEqual(try store.pendingOpCount(), 0)
        XCTAssertEqual(
            server.serverState(EntityKey(type: .habit, id: Fixture.habitId))?.field("name"),
            FieldValue("Run"))
    }

    func testPullMergesAndAdvancesTheWatermark() async throws {
        server.receive(fromThem(["name": .of("Jog")], at: 2_000))

        let outcome = try await engine.sync()

        XCTAssertEqual(outcome.changesApplied, 1)
        XCTAssertEqual(try store.watermark(), 1)
        XCTAssertEqual(try engine.load(.habit, Fixture.habitId)?.field("name"), FieldValue("Jog"))
    }

    /// A local edit is durable before any network exists. This is the offline-first claim, and
    /// it is one assertion.
    func testLocalEditsSurviveWithNoServerAtAll() throws {
        server.failNextExchanges = 99
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))

        XCTAssertEqual(try engine.load(.habit, Fixture.habitId)?.field("name"), FieldValue("Run"))
        XCTAssertEqual(try store.pendingOpCount(), 1)
    }

    /// The failure the engine sees most: nothing is acknowledged, nothing advances, and the
    /// work is exactly where it was.
    func testAFailedRoundTripLosesNothing() async throws {
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))
        server.failNextExchanges = 1

        do {
            _ = try await engine.sync()
            XCTFail("expected the exchange to fail")
        } catch let error as TransportError {
            XCTAssertTrue(error.retryable)
        }

        XCTAssertEqual(try store.pendingOpCount(), 1)
        XCTAssertEqual(try store.watermark(), 0)

        // And the retry goes through, with the same op id — which is what makes the replay
        // safe on the server.
        let outcome = try await engine.sync()
        XCTAssertEqual(outcome.opsAcknowledged, 1)
        XCTAssertEqual(server.logSize, 1)
    }

    /// A push whose response was lost: the server committed, the client never heard. The
    /// replay must be acknowledged rather than re-logged, or the outbox never drains.
    func testAReplayedPushIsAcknowledgedAndNotDoubleApplied() async throws {
        let op = try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))
        // Commit it behind the client's back, exactly as a lost response would leave things.
        server.receive(op)
        XCTAssertEqual(server.logSize, 1)

        let outcome = try await engine.sync()

        XCTAssertEqual(outcome.opsAcknowledged, 1)
        XCTAssertEqual(server.logSize, 1, "the replay must not be logged twice")
        XCTAssertEqual(try store.pendingOpCount(), 0)
    }

    /// An at-least-once network delivers every page twice. Merge is idempotent so this must be
    /// invisible in the result.
    func testDuplicatedPagesAreHarmless() async throws {
        server.duplicateEveryPage = true
        server.receive(fromThem(["name": .of("Jog")], at: 2_000))

        _ = try await engine.sync()

        XCTAssertEqual(try engine.load(.habit, Fixture.habitId)?.field("name"), FieldValue("Jog"))
        XCTAssertEqual(try store.watermark(), 1)
    }

    /// A server naming ops this device never pushed is confused or hostile. Dropping the outbox
    /// on its say-so would lose the edit outright.
    func testPhantomAcknowledgementsDoNotClearTheOutbox() async throws {
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))
        server.phantomAcks = [UUID(), UUID()]

        let outcome = try await engine.sync()

        XCTAssertEqual(outcome.opsAcknowledged, 1, "only our own op counts")
        XCTAssertEqual(try store.pendingOpCount(), 0)
    }

    // MARK: - Resync

    /// The ADR-003 path. State goes; the outbox does not — those ops are the device's own
    /// un-pushed work, and a server-side retention decision is no reason to bin them.
    func testResyncWipesStateAndKeepsTheOutbox() async throws {
        server.receive(fromThem(["name": .of("Jog")], at: 2_000))
        _ = try await engine.sync()
        XCTAssertEqual(try store.watermark(), 1)

        // A local edit that has not been pushed yet, and a server that now demands a rebuild.
        try engine.upsert(.habit, Fixture.habitId, field: "colour", value: .of("red"))
        server.demandResyncTimes = 1

        let outcome = try await engine.sync()

        XCTAssertTrue(outcome.resynced)
        // The un-pushed op was acknowledged on the same exchange that demanded the resync, so
        // it is not lost; the bootstrap that follows brings the state back.
        XCTAssertEqual(try store.pendingOpCount(), 0)
        XCTAssertEqual(try engine.load(.habit, Fixture.habitId)?.field("colour"), FieldValue("red"))
    }

    /// A resync demanded twice in one sync means resetting did not satisfy the server. Looping
    /// would wipe local state repeatedly while making no progress, so it is a hard failure.
    func testASecondResyncInOneSyncIsFatalRatherThanALoop() async throws {
        server.demandResyncTimes = 2

        do {
            _ = try await engine.sync()
            XCTFail("expected the second resync demand to fail the sync")
        } catch let error as TransportError {
            XCTAssertFalse(error.retryable, "this needs a human, not a backoff")
        }
    }

    // MARK: - Guards

    /// A server that claims more pages while returning a cursor that never moves would spin
    /// this loop until the page cap on every sync, forever.
    func testTheProgressGuardStopsAnUnproductiveLoop() async throws {
        server.freezeCursor = true

        let outcome = try await engine.sync()

        XCTAssertEqual(outcome.pagesFetched, 1)
        XCTAssertTrue(outcome.moreRemaining)
    }

    /// One device with an absurd clock must not be able to drag every replica's clock forward
    /// with it — that would starve every honest write of the ability to win a conflict.
    func testAnAbsurdlySkewedChangeIsRejectedAndTheWatermarkStaysPut() async throws {
        let farFuture = Hlc(
            physicalMillis: time.currentTimeMillis() + HlcClock.defaultMaxDriftMillis + 60_000,
            logical: 0, nodeId: them)
        server.receive(Fixture.upsert(["name": .of("From 2038")], at: farFuture))

        do {
            _ = try await engine.sync()
            XCTFail("expected the skewed change to be rejected")
        } catch is ClockDriftError {
            // expected
        }

        XCTAssertEqual(try store.watermark(), 0, "the page must be re-offered later")
        XCTAssertNil(try engine.load(.habit, Fixture.habitId))
    }

    // MARK: - Conflict reporting

    /// ADR-001 accepts losing the older write *on the condition that the loss is surfaced*.
    func testAnOverwrittenLocalWriteIsReported() async throws {
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))
        // The other device wrote the same field later, while this one was offline.
        server.receive(fromThem(["name": .of("Jog")], at: 5_000))

        let outcome = try await engine.sync()

        XCTAssertEqual(try engine.load(.habit, Fixture.habitId)?.field("name"), FieldValue("Jog"))
        XCTAssertEqual(outcome.lostLocalWrites.count, 1)
        let conflict = try XCTUnwrap(outcome.lostLocalWrites.first)
        XCTAssertEqual(conflict.kind, .fieldOverwritten)
        XCTAssertEqual(conflict.field, "name")
        XCTAssertEqual(conflict.loser.nodeId, us)
    }

    /// A successful round trip carries this device's own ops back down the pull stream.
    /// Reporting those as conflicts would manufacture a notice out of every sync.
    func testOurOwnEchoedOpsAreNotReportedAsConflicts() async throws {
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))

        let outcome = try await engine.sync()

        XCTAssertEqual(outcome.conflictsObserved, 0)
        XCTAssertTrue(outcome.conflicts.isEmpty)
    }

    /// The ADR-003 headline case, which no register contest reveals: the delete landed on an
    /// unset lifecycle register, so merge saw nothing remarkable, and the field writes survive
    /// intact. The loss is one of visibility.
    func testADeleteElsewhereThatHidesOurWorkIsReported() async throws {
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Evening Run"))
        _ = try await engine.sync()

        server.receive(
            Change.delete(
                type: .habit, entityId: Fixture.habitId,
                hlc: Hlc(physicalMillis: 5_000, logical: 0, nodeId: them)))

        let outcome = try await engine.sync()

        let conflict = try XCTUnwrap(outcome.lostLocalWrites.first)
        XCTAssertEqual(conflict.kind, .hiddenByDelete)
        XCTAssertEqual(conflict.winner.nodeId, them)
        XCTAssertEqual(conflict.loser.nodeId, us)
        // The field write itself survives, which is what lets a restore bring it back intact.
        let record = try XCTUnwrap(try engine.load(.habit, Fixture.habitId))
        XCTAssertFalse(record.visible)
        XCTAssertEqual(record.field("name"), FieldValue("Evening Run"))
    }

    /// A delete this device performed, arriving back from the server, is not news.
    func testOurOwnDeleteComingBackIsNotReported() async throws {
        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))
        try engine.delete(.habit, Fixture.habitId)

        let outcome = try await engine.sync()

        XCTAssertTrue(outcome.lostLocalWrites.isEmpty)
    }

    // MARK: - Convergence

    /// Two devices, edits made while apart, delivered in opposite orders. The point of the
    /// whole design in one test.
    func testTwoDevicesConvergeRegardlessOfDeliveryOrder() async throws {
        let otherStore = InMemoryLocalStore()
        let other = SyncEngine(
            clock: HlcClock(nodeId: them, timeSource: FakeClock(1_000)),
            store: otherStore, transport: server)

        try engine.upsert(.habit, Fixture.habitId, field: "name", value: .of("Run"))
        try other.upsert(.habit, Fixture.habitId, field: "colour", value: .of("red"))
        try other.upsert(.habit, Fixture.habitId, field: "name", value: .of("Jog"))

        // Push both, then let each drain everything the other sent.
        _ = try await engine.sync()
        _ = try await other.sync()
        _ = try await engine.sync()
        _ = try await other.sync()

        let mine = try XCTUnwrap(try engine.load(.habit, Fixture.habitId))
        let theirs = try XCTUnwrap(try other.load(.habit, Fixture.habitId))
        XCTAssertEqual(mine.fields, theirs.fields)
        XCTAssertEqual(mine.fieldClocks, theirs.fieldClocks)
        XCTAssertEqual(mine.deleted, theirs.deleted)
        // Disjoint edits both survived; the contested one was decided the same way on both.
        XCTAssertEqual(mine.field("colour"), FieldValue("red"))
    }
}
