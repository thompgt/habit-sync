import XCTest

@testable import HabitSyncCore

/// The clock's total order is what every merge decision reduces to, so these assert the
/// ordering itself rather than anything built on it.
final class HlcTests: XCTestCase {

    func testOrdersByPhysicalTimeFirst() {
        let earlier = Hlc(physicalMillis: 100, logical: 99, nodeId: "z")
        let later = Hlc(physicalMillis: 101, logical: 0, nodeId: "a")
        XCTAssertTrue(later.isAfter(earlier))
        XCTAssertTrue(earlier.isBefore(later))
    }

    func testOrdersByLogicalCounterWithinAMillisecond() {
        let first = Hlc(physicalMillis: 100, logical: 1, nodeId: "z")
        let second = Hlc(physicalMillis: 100, logical: 2, nodeId: "a")
        XCTAssertTrue(second.isAfter(first))
    }

    /// The tiebreak the convergence argument rests on. Without it these two compare equal,
    /// merge's strictly-greater rule declines both, and two replicas that saw them in
    /// different orders keep different values forever.
    func testBreaksTiesByNodeId() {
        let a = Hlc(physicalMillis: 100, logical: 1, nodeId: "device-a")
        let b = Hlc(physicalMillis: 100, logical: 1, nodeId: "device-b")
        XCTAssertTrue(b.isAfter(a))
        XCTAssertNotEqual(a, b)
    }

    /// Ordering must agree with equality: HLCs are used as dictionary keys and sort keys, and
    /// a disagreement would make merge results depend on collection internals.
    func testComparisonIsConsistentWithEquality() {
        let a = Hlc(physicalMillis: 7, logical: 3, nodeId: "n")
        let b = Hlc(physicalMillis: 7, logical: 3, nodeId: "n")
        XCTAssertEqual(a, b)
        XCTAssertFalse(a.isAfter(b))
        XCTAssertFalse(a.isBefore(b))
    }

    func testCompactStringRoundTrips() throws {
        let original = Hlc(physicalMillis: 1_699_000_000_000, logical: 4, nodeId: "b7c1e0a2")
        let parsed = try Hlc.parse(original.compactString)
        XCTAssertEqual(parsed, original)
    }

    /// A node id is UUID text and contains no separator, but the parser splits with a limit
    /// anyway — defence against data written by an older version.
    func testParseKeepsTheWholeNodeIdEvenIfItLooksSplittable() throws {
        let parsed = try Hlc.parse("5:2:node:with:colons")
        XCTAssertEqual(parsed.nodeId, "node:with:colons")
        XCTAssertEqual(parsed.physicalMillis, 5)
        XCTAssertEqual(parsed.logical, 2)
    }

    func testParseRejectsMalformedText() {
        XCTAssertThrowsError(try Hlc.parse("nonsense"))
        XCTAssertThrowsError(try Hlc.parse("5:2"))
        XCTAssertThrowsError(try Hlc.parse("five:2:node"))
        XCTAssertThrowsError(try Hlc.parse("-1:0:node"))
        XCTAssertThrowsError(try Hlc.parse("5:0:"))
    }

    /// The counter is bounded at 32 bits because the JVM parses it with Integer.parseInt. A
    /// value this side could encode and that side could not would be a one-way protocol.
    func testParseRejectsALogicalCounterWiderThanTheJvmCanRead() {
        XCTAssertThrowsError(try Hlc.parse("5:\(Int64(Int32.max) + 1):node"))
    }

    func testMaxPrefersTheGreaterAndToleratesNil() {
        let low = Hlc(physicalMillis: 1, logical: 0, nodeId: "n")
        let high = Hlc(physicalMillis: 2, logical: 0, nodeId: "n")
        XCTAssertEqual(Hlc.max(low, high), high)
        XCTAssertEqual(Hlc.max(high, low), high)
        XCTAssertEqual(Hlc.max(nil, low), low)
        XCTAssertEqual(Hlc.max(low, nil), low)
        XCTAssertNil(Hlc.max(nil, nil))
    }
}
