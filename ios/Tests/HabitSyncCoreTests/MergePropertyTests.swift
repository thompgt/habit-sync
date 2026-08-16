import XCTest

@testable import HabitSyncCore

/// A seeded generator, so a failure is reproducible from the message alone.
///
/// The alternative — `SystemRandomNumberGenerator` — gives a test that fails once on someone
/// else's machine and never again, which for a convergence property is the worst possible
/// outcome: it is exactly the class of bug that only shows up rarely.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) { state = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407 }

    mutating func next() -> UInt64 {
        // splitmix64: small, well-distributed, and identical run to run.
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// The three algebraic properties the network forces on merge. Everything else in the sync
/// layer is arranged so that these hold; if one of them breaks, two devices can hold different
/// state forever and no amount of re-syncing repairs it.
final class MergePropertyTests: XCTestCase {

    private let engine = MergeEngine()
    private let fields = ["name", "colour", "weeklyTarget"]
    private let nodes = ["device-a", "device-b", "device-c"]

    /// Builds a change over a small alphabet, so collisions on the same field and the same
    /// millisecond happen often — the interesting case, not the rare one.
    private func randomChange(_ rng: inout SeededGenerator) -> Change {
        let hlc = Hlc(
            physicalMillis: Int64.random(in: 1...6, using: &rng),
            logical: Int.random(in: 0...2, using: &rng),
            nodeId: nodes.randomElement(using: &rng)!)

        switch Int.random(in: 0...5, using: &rng) {
        case 0:
            return Fixture.delete(at: hlc)
        case 1:
            return Fixture.restore(at: hlc)
        default:
            let field = fields.randomElement(using: &rng)!
            let value = Int.random(in: 0...4, using: &rng)
            // Value 0 is a clear. It has to be in the alphabet: a cleared register and an
            // unwritten one are different states, and a property test that never produced one
            // would leave the difference untested.
            return Fixture.upsert([field: value == 0 ? .null : .of("v\(value)")], at: hlc)
        }
    }

    private func fold(_ changes: [Change]) -> EntityRecord? {
        engine.mergeAll(nil, changes)
    }

    /// Two devices receiving the same changes in different orders must agree.
    func testMergeIsCommutative() {
        for seed in UInt64(1)...200 {
            var rng = SeededGenerator(seed: seed)
            let changes = (0..<6).map { _ in randomChange(&rng) }
            var shuffled = changes
            shuffled.shuffle(using: &rng)

            XCTAssertEqual(fold(changes), fold(shuffled), "order changed the result (seed \(seed))")
        }
    }

    /// An at-least-once network delivers pages twice. Applying one again must change nothing.
    func testMergeIsIdempotent() {
        for seed in UInt64(1)...200 {
            var rng = SeededGenerator(seed: seed)
            let changes = (0..<6).map { _ in randomChange(&rng) }
            let once = fold(changes)
            let twice = engine.mergeAll(once, changes)

            XCTAssertEqual(once, twice, "reapplying changed the result (seed \(seed))")
        }
    }

    /// Grouping is irrelevant, which is what lets the transport page however it likes.
    func testMergeIsAssociative() {
        for seed in UInt64(1)...200 {
            var rng = SeededGenerator(seed: seed)
            let changes = (0..<6).map { _ in randomChange(&rng) }
            let split = Int.random(in: 1..<changes.count, using: &rng)

            let wholesale = fold(changes)
            let paged = engine.mergeAll(
                fold(Array(changes.prefix(split))), Array(changes.suffix(from: split)))

            XCTAssertEqual(wholesale, paged, "paging changed the result (seed \(seed))")
        }
    }

    /// The regression the JVM simulator injects deliberately: drop the nodeId tiebreak and
    /// convergence fails. Asserted here as the ordering property it rests on — two writes that
    /// differ only by node must still be strictly ordered, so both replicas pick the same
    /// winner regardless of arrival order.
    func testConcurrentWritesFromDifferentNodesAreStillOrdered() {
        let a = Fixture.upsert(["name": .of("A")], at: Fixture.hlc(3, 1, "device-a"))
        let b = Fixture.upsert(["name": .of("B")], at: Fixture.hlc(3, 1, "device-b"))

        let oneWay = fold([a, b])
        let otherWay = fold([b, a])

        XCTAssertEqual(oneWay, otherWay)
        XCTAssertEqual(oneWay?.field("name"), FieldValue("B"), "the higher node id must win consistently")
    }
}
