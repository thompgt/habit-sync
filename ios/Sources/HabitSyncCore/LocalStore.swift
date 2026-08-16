import Foundation

/// The device's durable state, behind the narrowest interface the engine can work with:
/// merged entity records, an outbox of un-acknowledged local ops, and the pull watermark.
///
/// On iOS this is SQLite. In tests it is ``InMemoryLocalStore``. The engine cannot tell the
/// difference, which is the point.
///
/// ## Atomicity is the contract
///
/// Three methods here are required to be **atomic and durable** — ``applyLocal(merged:op:)``,
/// ``applyRemote(merged:nextSeq:clock:)`` and ``resetForResync()``. This is not incidental;
/// each pairs a state write with a bookkeeping write, and a crash between the two halves
/// corrupts the device in a way no amount of retrying repairs:
///
/// - `applyLocal` writes the entity *and* enqueues the op. Entity without op means the
///   user's edit shows on screen and never reaches the server — silent data loss,
///   discovered weeks later.
/// - `applyRemote` writes the entities *and* advances the watermark. Advancing first means
///   a crash skips those changes permanently, because the device will never ask for that
///   range again.
///
/// The reverse orderings are all survivable: an op without its entity re-merges harmlessly,
/// and a watermark behind the applied state re-pulls changes that merge to a no-op. Merge is
/// idempotent precisely so that the safe failure mode is the one that costs bandwidth rather
/// than data. An implementation that cannot offer a transaction must at minimum order its
/// writes so the surviving prefix is the recoverable one.
///
/// Implementations must be safe to call from a background sync task while the UI reads —
/// hence `Sendable`, and hence the serialisation each implementation does internally.
///
/// Methods throw rather than trapping because the failures here are real and recoverable on
/// a phone: a full disk, a database locked by a crashed extension, a file protected while
/// the device is locked. A local edit that cannot be persisted must be reported to the
/// person who typed it, not swallowed and not fatal.
public protocol LocalStore: AnyObject, Sendable {

    /// The merged state of `key`, or `nil` if this device has never seen it.
    func load(_ key: EntityKey) throws -> EntityRecord?

    /// Records a locally originated change: writes the merged entity and enqueues the op for
    /// push, atomically.
    ///
    /// - Parameters:
    ///   - merged: the entity state after merging `op` — the engine has already done the
    ///     merge, so the store just persists it.
    ///   - op: the change to hand to the server on the next sync.
    func applyLocal(merged: EntityRecord, op: Change) throws

    /// Records a batch pulled from the server: writes the merged entities and advances the
    /// watermark to `nextSeq`, atomically.
    ///
    /// Called once per page. Passing the whole page rather than one record at a time is what
    /// lets an implementation wrap it in a single transaction.
    ///
    /// - Parameters:
    ///   - merged: entity states after merging the page, at most one entry per entity.
    ///   - nextSeq: the new watermark; never lower than the current one.
    ///   - clock: this device's clock reading after observing every change in the page;
    ///     persisted in the same transaction, see ``lastClock()``.
    func applyRemote(merged: [EntityRecord], nextSeq: Int64, clock: Hlc) throws

    /// The highest `serverSeq` durably applied; 0 if nothing ever has been.
    func watermark() throws -> Int64

    /// Un-acknowledged local ops, oldest first.
    ///
    /// Order is a courtesy to the server's logs — merge does not depend on it.
    func pendingOps(limit: Int) throws -> [Change]

    /// The total number of un-acknowledged local ops, for backlog reporting.
    func pendingOpCount() throws -> Int

    /// Removes ops the server has confirmed committed. Unknown ids are ignored.
    func acknowledgeOps(_ opIds: [UUID]) throws

    /// Wipes merged entity state and resets the watermark to 0, **keeping the outbox**.
    ///
    /// Keeping it is deliberate. A resync means the server has garbage-collected log entries
    /// this device never read; it says nothing about the device's own un-pushed edits, and
    /// discarding a week of offline work to recover from a server-side retention decision
    /// would be an unforced data loss. Those ops are re-pushed, and the server merges them
    /// against whatever it holds — the ordinary path.
    func resetForResync() throws

    /// The highest clock reading this store has persisted, or `nil` on a fresh install.
    ///
    /// Implementations derive it from the writes they already accept — `op.hlc` in
    /// ``applyLocal(merged:op:)`` and the `clock` argument to
    /// ``applyRemote(merged:nextSeq:clock:)`` — rather than from a separate save call.
    ///
    /// There is deliberately no `saveClock` method, because a separate call could land on
    /// the other side of a crash from the op it stamped. That is the one failure this design
    /// cannot tolerate: reusing an HLC for two different local changes gives two writes an
    /// identical timestamp, and merge's strictly-greater rule then lets replicas that see
    /// them in different orders pick different winners. Duplicate timestamps are the one way
    /// to break convergence from the client side, so the clock is persisted by the same
    /// atomic write as the change that used it, or not at all.
    ///
    /// Note ``resetForResync()`` must **not** clear this. The clock is this device's
    /// monotonic history; a resync discards replicated state, not identity.
    func lastClock() throws -> Hlc?
}

/// Bulk reads the sync engine never needs but a user interface always does.
///
/// Kept off ``LocalStore`` on purpose: the engine's interface is deliberately the narrowest
/// thing that can drive the protocol, and every method added to it is another one a test
/// double has to implement correctly.
public protocol RecordQuerying: AnyObject, Sendable {
    /// Every entity this device holds, tombstones included.
    func allRecords() throws -> [EntityRecord]

    /// Every entity of one type this device holds, tombstones included.
    func records(ofType type: EntityType) throws -> [EntityRecord]
}

extension RecordQuerying {
    /// The entities the application would show the user.
    public func visibleRecords(ofType type: EntityType) throws -> [EntityRecord] {
        try records(ofType: type).filter(\.visible)
    }
}
