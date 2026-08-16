import Foundation

/// The record of a single conflict decision — what the engine was asked to do, what it
/// decided, and the two clock readings behind the decision.
///
/// Merge returns these rather than logging them, because the core has no logger and
/// because "who won and why" is information the server, the client and the tests all want
/// in different forms: the server records it as a metric, the client surfaces it to the
/// user when their edit was discarded, and tests assert on it directly.
///
/// Discarded work that the user is never told about is a bug. Discarded work under a
/// documented rule, reported, is a trade-off. These records are what make the difference.
public struct Resolution: Hashable, Sendable {

    /// Which of an entity's two independent register groups a resolution concerns.
    public enum Target: Hashable, Sendable {
        case field
        case lifecycle
    }

    /// The outcome of comparing an incoming clock against the one in place.
    public enum Verdict: Hashable, Sendable {
        /// The incoming change won and was written.
        case applied
        /// The register already held a strictly greater clock; the incoming write was dropped.
        case superseded
    }

    public let target: Target
    /// The field name for ``Target/field``; `nil` for ``Target/lifecycle``.
    public let field: String?
    public let verdict: Verdict
    /// The incoming change's clock.
    public let incoming: Hlc
    /// The clock already in place, or `nil` if the register was unset.
    public let existing: Hlc?

    init(target: Target, field: String?, verdict: Verdict, incoming: Hlc, existing: Hlc?) {
        precondition(
            target != .field || field != nil, "FIELD resolutions must name a field")
        precondition(
            target != .lifecycle || field == nil, "LIFECYCLE resolutions must not name a field")
        self.target = target
        self.field = field
        self.verdict = verdict
        self.incoming = incoming
        self.existing = existing
    }

    static func field(_ name: String, _ verdict: Verdict, incoming: Hlc, existing: Hlc?) -> Resolution {
        Resolution(target: .field, field: name, verdict: verdict, incoming: incoming, existing: existing)
    }

    static func lifecycle(_ verdict: Verdict, incoming: Hlc, existing: Hlc?) -> Resolution {
        Resolution(target: .lifecycle, field: nil, verdict: verdict, incoming: incoming, existing: existing)
    }

    /// True when a write was dropped in favour of an existing, newer one.
    public var lostConflict: Bool { verdict == .superseded }
}

/// The outcome of merging one ``Change`` into one ``EntityRecord``: the resulting state,
/// plus a decision record per contested register.
public struct MergeResult: Hashable, Sendable {
    public let state: EntityRecord
    /// One entry per register the change touched.
    public let resolutions: [Resolution]

    init(state: EntityRecord, resolutions: [Resolution]) {
        self.state = state
        self.resolutions = resolutions
    }

    /// Whether the merge altered anything.
    ///
    /// Lets callers skip a database write for changes that are entirely superseded — the
    /// common case when re-applying a duplicated or replayed page.
    public var mutated: Bool { resolutions.contains { $0.verdict == .applied } }

    /// Writes that were dropped because a newer value was already in place.
    public var superseded: [Resolution] { resolutions.filter(\.lostConflict) }
}
