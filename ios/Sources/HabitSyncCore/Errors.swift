import Foundation

/// A value arriving from outside this process could not be interpreted: a malformed HLC,
/// an unknown entity type, an op missing its id.
///
/// The JVM side raises `IllegalArgumentException` for all of these, and the shape is the
/// same here for the same reason: it describes a defect in the *input*, not in the
/// machine handling it. The server answers a push carrying one with a 400, and this client
/// treats a pull carrying one as permanently — not temporarily — undeliverable, because
/// the identical bytes will fail identically forever.
public struct CodecError: Error, CustomStringConvertible, Sendable {
    public let message: String

    public init(_ message: String) { self.message = message }

    public static func malformed(_ message: String) -> CodecError { CodecError(message) }

    public var description: String { message }
}

/// An inbound ``Hlc`` claims a physical time so far ahead of this device's clock that
/// accepting it would be indistinguishable from a misconfigured or malicious peer.
///
/// An HLC absorbs skew by advancing to match whatever it observes. That is the desired
/// behaviour for milliseconds of jitter and disastrous for a device whose clock is set to
/// 2038: every replica would ratchet forward to that value and stay there, permanently
/// starving every honest write of the ability to win a conflict. Bounding the absorption
/// keeps one bad clock a local failure instead of a whole-account one.
public struct ClockDriftError: Error, CustomStringConvertible, Sendable {
    public let offending: Hlc
    public let localMillis: Int64
    public let maxDriftMillis: Int64

    public init(offending: Hlc, localMillis: Int64, maxDriftMillis: Int64) {
        self.offending = offending
        self.localMillis = localMillis
        self.maxDriftMillis = maxDriftMillis
    }

    public var description: String {
        """
        HLC physical time \(offending.physicalMillis) is \
        \(offending.physicalMillis - localMillis) ms ahead of local time \(localMillis), \
        exceeding the \(maxDriftMillis) ms limit (node \(offending.nodeId))
        """
    }
}

/// A sync round trip did not complete: no connectivity, a timeout, a 5xx, a malformed
/// response.
///
/// On an offline-first client a failed sync is the *expected* case, not an exceptional
/// one — the app spends much of its life in a tunnel. ``retryable`` is what separates "the
/// network was rubbish", which wants backoff, from "this device's credentials are gone",
/// which wants the user. A client that retried the second forever would stop syncing
/// permanently while reporting nothing but "sync failed".
public struct TransportError: Error, CustomStringConvertible {
    public let message: String
    public let retryable: Bool
    /// The failure underneath, kept for diagnostics. Not `Sendable`-checked because
    /// `Error` existentials are not; it is only ever read for its description.
    public let underlying: (any Error)?

    public init(_ message: String, retryable: Bool = true, underlying: (any Error)? = nil) {
        self.message = message
        self.retryable = retryable
        self.underlying = underlying
    }

    public var description: String {
        guard let underlying else { return message }
        return "\(message): \(underlying)"
    }
}

// TransportError carries an existential Error, which the compiler cannot prove Sendable.
// The value is immutable and read only for its text, so the unchecked conformance above is
// sound; this silences the concurrency checker without weakening anything that matters.
extension TransportError: @unchecked Sendable {}
