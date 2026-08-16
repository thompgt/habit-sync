import Foundation

/// A device's Hybrid Logical Clock, implementing the send/receive rules from
/// Kulkarni et al.
///
/// Two operations drive it:
///
/// - ``tick()`` — stamp a locally originated event.
/// - ``observe(_:)`` — fold in a timestamp received from a peer, so that any event this
///   device subsequently stamps is ordered strictly after everything it has seen. This is
///   what makes the clock *causal* rather than merely a timestamp generator, and it must
///   be called for every inbound change.
///
/// Thread-safe: all mutation happens under a lock. Contention is negligible (a phone's
/// sync task plus its UI) and the critical section is a handful of comparisons, so a lock
/// is the right trade against making the whole type an actor — `tick()` is called from
/// synchronous UI code, and an actor would force every local edit to become `await`.
public final class HlcClock: @unchecked Sendable {

    /// Default tolerance for a peer whose wall clock runs ahead of ours.
    public static let defaultMaxDriftMillis: Int64 = 5 * 60 * 1000

    /// Requires ~2 billion events inside one stalled millisecond. Bounded at 32 bits
    /// because the JVM side parses this component with `Integer.parseInt`.
    private static let maxLogical = Int(Int32.max)

    public let nodeId: String
    private let timeSource: any TimeSource
    private let maxDriftMillis: Int64

    private let lock = NSLock()
    private var physicalMillis: Int64 = 0
    private var logical: Int = 0

    public init(
        nodeId: String,
        timeSource: any TimeSource,
        maxDriftMillis: Int64 = HlcClock.defaultMaxDriftMillis
    ) {
        precondition(maxDriftMillis >= 0, "maxDrift must not be negative")
        // Validate the node id eagerly rather than at the first tick, so a bad id fails at
        // construction instead of hours later on the first sync.
        _ = Hlc(physicalMillis: 0, logical: 0, nodeId: nodeId)
        self.nodeId = nodeId
        self.timeSource = timeSource
        self.maxDriftMillis = maxDriftMillis
    }

    /// Rebuilds a clock from its persisted state after process death.
    ///
    /// Restoring is not optional. A device that restarts from zero re-stamps edits with
    /// timestamps at or below ones it has already issued, so its own fresh writes lose to
    /// its own stale ones — and if the wall clock has not advanced past the stored
    /// reading, it can issue the very same timestamp twice, which is the one client-side
    /// way to break convergence outright.
    ///
    /// Unlike ``observe(_:)`` this applies **no drift check**, deliberately. The stored
    /// reading is this device's own history, not a peer's claim. A device whose user
    /// rolled the system clock backwards is exactly the case that must survive, and
    /// refusing to start is not a recovery strategy.
    ///
    /// - Parameter previous: the value from ``LocalStore/lastClock()``; its node id must
    ///   match, because a mismatch means the stored state belongs to a different device —
    ///   a restored backup, or a device id regenerated underneath us. Adopting it silently
    ///   would hide a bug that surfaces later as two devices sharing one node id, which
    ///   does break convergence.
    public static func restored(
        nodeId: String,
        timeSource: any TimeSource,
        maxDriftMillis: Int64 = HlcClock.defaultMaxDriftMillis,
        previous: Hlc
    ) throws -> HlcClock {
        guard previous.nodeId == nodeId else {
            throw CodecError(
                "Persisted clock belongs to node \(previous.nodeId), not \(nodeId)")
        }
        let clock = HlcClock(nodeId: nodeId, timeSource: timeSource, maxDriftMillis: maxDriftMillis)
        clock.physicalMillis = previous.physicalMillis
        clock.logical = previous.logical
        return clock
    }

    /// Stamps a locally originated event.
    ///
    /// Guaranteed strictly greater than every timestamp this clock has previously returned
    /// or observed, so a device's own edits are always ordered by their real sequence even
    /// when its wall clock stalls or jumps backwards.
    @discardableResult
    public func tick() -> Hlc {
        lock.lock()
        defer { lock.unlock() }

        let now = timeSource.currentTimeMillis()
        if now > physicalMillis {
            physicalMillis = now
            logical = 0
        } else {
            // Clock stalled within the same millisecond, or jumped backwards. Hold the
            // high-water physical time and advance logically so monotonicity survives.
            logical = Self.incrementLogical(logical)
        }
        return Hlc(physicalMillis: physicalMillis, logical: logical, nodeId: nodeId)
    }

    /// Folds a peer's timestamp into this clock and returns the clock's new value.
    ///
    /// Must be called for every inbound change before that change is merged. Skipping it
    /// lets this device later stamp an edit with a timestamp lower than one it has already
    /// seen, which silently makes fresh local edits lose to stale remote ones.
    ///
    /// - Throws: ``ClockDriftError`` if `remote` is more than the configured tolerance
    ///   ahead of local time.
    @discardableResult
    public func observe(_ remote: Hlc) throws -> Hlc {
        lock.lock()
        defer { lock.unlock() }

        let now = timeSource.currentTimeMillis()
        if remote.physicalMillis > now + maxDriftMillis {
            throw ClockDriftError(
                offending: remote, localMillis: now, maxDriftMillis: maxDriftMillis)
        }

        let maxPhysical = Swift.max(now, Swift.max(physicalMillis, remote.physicalMillis))

        if maxPhysical == physicalMillis && maxPhysical == remote.physicalMillis {
            // Both clocks agree on the millisecond: take the higher counter, step past it.
            logical = Self.incrementLogical(Swift.max(logical, remote.logical))
        } else if maxPhysical == physicalMillis {
            logical = Self.incrementLogical(logical)
        } else if maxPhysical == remote.physicalMillis {
            logical = Self.incrementLogical(remote.logical)
        } else {
            // Local wall clock leads both; it alone is enough to order this event.
            logical = 0
        }
        physicalMillis = maxPhysical

        return Hlc(physicalMillis: physicalMillis, logical: logical, nodeId: nodeId)
    }

    /// The current value, without advancing the clock.
    public func peek() -> Hlc {
        lock.lock()
        defer { lock.unlock() }
        return Hlc(physicalMillis: physicalMillis, logical: logical, nodeId: nodeId)
    }

    private static func incrementLogical(_ current: Int) -> Int {
        // Silently wrapping to a lower counter would corrupt the total order, so this is a
        // hard stop rather than a clamp. Reaching it means something upstream is badly
        // wrong: it takes Int32.max events inside one stalled millisecond.
        precondition(
            current < maxLogical,
            "HLC logical counter overflow: the physical clock has not advanced in \(maxLogical) events")
        return current + 1
    }
}
