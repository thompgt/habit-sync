import Foundation

/// A Hybrid Logical Clock timestamp: physical wall-clock time fused with a logical
/// counter, plus the identity of the node that produced it.
///
/// HLCs give a *total order* over events across devices without a central sequencer and
/// without trusting wall clocks:
///
/// - The **physical** component keeps ordering roughly aligned with human intuition, so
///   "the edit I made later" usually wins.
/// - The **logical** component preserves causality when the physical clock does not
///   advance, giving Lamport-clock guarantees: if `a` causally precedes `b`, then `a < b`.
/// - The **nodeId** tiebreak is **load-bearing**. Without it, two events with identical
///   physical and logical components compare equal, two replicas can pick different
///   winners, and convergence fails. Do not "simplify" it away — the JVM convergence
///   simulator deliberately injects its removal as a regression test.
///
/// This is a port of `sync-core`'s `Hlc`, and the two must stay byte-compatible: the
/// compact encoding below is what crosses the wire between an iPhone and the server, and
/// what a JVM client parses back.
public struct Hlc: Hashable, Sendable, Comparable, CustomStringConvertible {

    /// Separator used by ``compactString``; forbidden inside a node id.
    public static let fieldSeparator: Character = ":"

    /// Wall-clock milliseconds since the epoch, never negative.
    public let physicalMillis: Int64

    /// Counter disambiguating events within the same millisecond.
    ///
    /// Bounded to `Int32.max` rather than `Int.max` even though Swift's `Int` is wider:
    /// the JVM side parses this component with `Integer.parseInt`, so a value beyond
    /// 32 bits would encode here and fail to decode there.
    public let logical: Int

    /// Stable identifier of the originating device.
    public let nodeId: String

    /// Creates a timestamp.
    ///
    /// The invariants are checked with `precondition` rather than by throwing, because
    /// every caller inside this module supplies values it has already validated — a node
    /// id from the session, a counter from the clock. Untrusted input arrives as text and
    /// goes through ``parse(_:)``, which throws instead.
    public init(physicalMillis: Int64, logical: Int, nodeId: String) {
        precondition(physicalMillis >= 0, "physicalMillis must be >= 0, got \(physicalMillis)")
        precondition(logical >= 0, "logical must be >= 0, got \(logical)")
        precondition(!nodeId.isEmpty, "nodeId must not be empty")
        precondition(
            !nodeId.contains(Hlc.fieldSeparator),
            "nodeId must not contain '\(Hlc.fieldSeparator)', got: \(nodeId)")
        self.physicalMillis = physicalMillis
        self.logical = logical
        self.nodeId = nodeId
    }

    /// Total order: physical time, then logical counter, then node id.
    ///
    /// Consistent with `==`: this returns equal only for equal values. That consistency is
    /// required — HLCs are sort keys and dictionary keys, and an ordering that disagreed
    /// with equality would make merge results depend on collection internals.
    public static func < (lhs: Hlc, rhs: Hlc) -> Bool {
        if lhs.physicalMillis != rhs.physicalMillis {
            return lhs.physicalMillis < rhs.physicalMillis
        }
        if lhs.logical != rhs.logical {
            return lhs.logical < rhs.logical
        }
        // Java compares with String.compareTo, which orders by UTF-16 code unit. Node ids
        // are UUID text (ASCII), where Swift's `<` agrees, so the two ends order the same
        // values identically. Anything outside ASCII would need an explicit comparison.
        return lhs.nodeId < rhs.nodeId
    }

    /// Whether this timestamp strictly follows `other` in the total order.
    public func isAfter(_ other: Hlc) -> Bool { self > other }

    /// Whether this timestamp strictly precedes `other` in the total order.
    public func isBefore(_ other: Hlc) -> Bool { self < other }

    /// Whichever of the two timestamps is greater; ties return `a`.
    public static func max(_ a: Hlc?, _ b: Hlc?) -> Hlc? {
        guard let a else { return b }
        guard let b else { return a }
        return a >= b ? a : b
    }

    /// Encodes as `physicalMillis:logical:nodeId` — one sortable-ish string suitable for a
    /// single database column or a single JSON field.
    ///
    /// Note this encoding is *not* lexicographically ordered (physical time is not
    /// zero-padded). Always sort by comparing parsed values, never these strings.
    public var compactString: String {
        "\(physicalMillis)\(Hlc.fieldSeparator)\(logical)\(Hlc.fieldSeparator)\(nodeId)"
    }

    /// Inverse of ``compactString``.
    ///
    /// - Throws: ``CodecError`` if the text is not a well-formed HLC.
    public static func parse(_ encoded: String) throws -> Hlc {
        // maxSplits 2 so a node id is never split, even though the initialiser forbids
        // separators in it — defence in depth against data written by an older version.
        let parts = encoded.split(
            separator: fieldSeparator, maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3 else {
            throw CodecError.malformed("Malformed HLC, expected 'physical:logical:node': \(encoded)")
        }
        guard let physical = Int64(parts[0]), let logical = Int32(parts[1]) else {
            throw CodecError.malformed("Malformed HLC, non-numeric component: \(encoded)")
        }
        guard physical >= 0, logical >= 0, !parts[2].isEmpty else {
            throw CodecError.malformed("Malformed HLC, component out of range: \(encoded)")
        }
        return Hlc(physicalMillis: physical, logical: Int(logical), nodeId: String(parts[2]))
    }

    public var description: String { compactString }
}
