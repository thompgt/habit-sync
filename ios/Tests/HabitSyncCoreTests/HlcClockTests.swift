import XCTest

@testable import HabitSyncCore

/// A clock the test drives by hand. Skew and stalls are the conditions that separate a sync
/// engine that happens to work from one that is correct, and neither can be provoked against
/// the real system clock.
final class FakeClock: TimeSource, @unchecked Sendable {
    private let lock = NSLock()
    private var now: Int64

    init(_ start: Int64) { now = start }

    func currentTimeMillis() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return now
    }

    func set(_ value: Int64) {
        lock.lock()
        now = value
        lock.unlock()
    }

    func advance(_ millis: Int64) {
        lock.lock()
        now += millis
        lock.unlock()
    }
}

final class HlcClockTests: XCTestCase {

    func testTickTracksTheWallClock() {
        let time = FakeClock(1_000)
        let clock = HlcClock(nodeId: "a", timeSource: time)

        let first = clock.tick()
        time.advance(5)
        let second = clock.tick()

        XCTAssertEqual(first.physicalMillis, 1_000)
        XCTAssertEqual(second.physicalMillis, 1_005)
        XCTAssertEqual(second.logical, 0)
    }

    /// A stalled millisecond must still produce ordered timestamps, or two edits made in the
    /// same millisecond become indistinguishable and merge cannot order them.
    func testTickAdvancesLogicallyWhenTheClockStalls() {
        let clock = HlcClock(nodeId: "a", timeSource: FakeClock(1_000))
        let first = clock.tick()
        let second = clock.tick()
        let third = clock.tick()

        XCTAssertEqual([first.logical, second.logical, third.logical], [0, 1, 2])
        XCTAssertTrue(second.isAfter(first))
        XCTAssertTrue(third.isAfter(second))
    }

    /// The case that matters most: a user rolling their clock back must not make the device
    /// reissue timestamps it has already used.
    func testTickStaysMonotonicWhenTheClockGoesBackwards() {
        let time = FakeClock(10_000)
        let clock = HlcClock(nodeId: "a", timeSource: time)
        let before = clock.tick()

        time.set(1_000)
        let after = clock.tick()

        XCTAssertTrue(after.isAfter(before))
        XCTAssertEqual(after.physicalMillis, 10_000)
    }

    /// What makes the clock causal rather than a timestamp generator: after seeing a peer's
    /// write, everything this device stamps must order strictly after it.
    func testObserveOrdersLaterTicksAfterWhatWasSeen() throws {
        let clock = HlcClock(nodeId: "a", timeSource: FakeClock(1_000))
        let remote = Hlc(physicalMillis: 5_000, logical: 3, nodeId: "b")

        _ = try clock.observe(remote)
        let mine = clock.tick()

        XCTAssertTrue(mine.isAfter(remote))
    }

    func testObserveRejectsAbsurdSkew() {
        let clock = HlcClock(nodeId: "a", timeSource: FakeClock(1_000), maxDriftMillis: 60_000)
        let fromTheFuture = Hlc(physicalMillis: 1_000 + 60_001, logical: 0, nodeId: "b")

        XCTAssertThrowsError(try clock.observe(fromTheFuture)) { error in
            guard let drift = error as? ClockDriftError else {
                return XCTFail("expected a ClockDriftError, got \(error)")
            }
            XCTAssertEqual(drift.offending, fromTheFuture)
        }
        // And the clock is untouched, so one bad peer cannot ratchet this device forward.
        XCTAssertEqual(clock.peek().physicalMillis, 0)
    }

    func testObserveAcceptsSkewInsideTheTolerance() throws {
        let clock = HlcClock(nodeId: "a", timeSource: FakeClock(1_000), maxDriftMillis: 60_000)
        let slightlyAhead = Hlc(physicalMillis: 1_000 + 59_999, logical: 0, nodeId: "b")
        let result = try clock.observe(slightlyAhead)
        XCTAssertEqual(result.physicalMillis, slightlyAhead.physicalMillis)
    }

    /// Restoring is the reason `lastClock` is persisted in the same transaction as the op that
    /// used it. A device restarting from zero reissues timestamps, and duplicate timestamps
    /// are the one client-side way to break convergence outright.
    func testRestoredResumesAboveThePersistedReading() throws {
        let stored = Hlc(physicalMillis: 9_000, logical: 7, nodeId: "a")
        // Wall clock behind the stored reading — a device whose user moved it back, which is
        // exactly the case that must survive rather than refuse to start.
        let clock = try HlcClock.restored(nodeId: "a", timeSource: FakeClock(1_000), previous: stored)

        let next = clock.tick()
        XCTAssertTrue(next.isAfter(stored))
        XCTAssertEqual(next.physicalMillis, 9_000)
        XCTAssertEqual(next.logical, 8)
    }

    /// A stored clock belonging to another node means a restored backup or a regenerated
    /// device id. Adopting it silently hides a bug that surfaces as two devices sharing one
    /// node identity — which does break convergence.
    func testRestoredRejectsAnotherDevicesClock() {
        let stored = Hlc(physicalMillis: 9_000, logical: 7, nodeId: "b")
        XCTAssertThrowsError(
            try HlcClock.restored(nodeId: "a", timeSource: FakeClock(1_000), previous: stored))
    }
}
