import Foundation

/// Wall-clock time, injected rather than read statically.
///
/// This exists so tests can give each virtual device its own skewed, frozen or
/// fast-forwarded clock. Clock skew is one of the two conditions — the other being network
/// reordering — that separates a sync engine that happens to work from one that is
/// actually correct, and neither can be tested against `Date()`.
public protocol TimeSource: Sendable {
    /// Milliseconds since the Unix epoch.
    func currentTimeMillis() -> Int64
}

/// The real device clock.
public struct SystemTimeSource: TimeSource {
    public init() {}

    public func currentTimeMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded())
    }
}

/// A clock offset from the device clock by a fixed amount — for simulating skew.
public struct SkewedTimeSource: TimeSource {
    public let offsetMillis: Int64

    public init(offsetMillis: Int64) { self.offsetMillis = offsetMillis }

    public func currentTimeMillis() -> Int64 {
        SystemTimeSource().currentTimeMillis() + offsetMillis
    }
}

extension TimeSource where Self == SystemTimeSource {
    /// `.system` at a use site, matching the JVM `TimeSource.system()` factory.
    public static var system: SystemTimeSource { SystemTimeSource() }
}
