import Foundation

/// The client half of the sync protocol: stamp local edits, push them, pull what the device
/// missed, merge it, and move the watermark — without ever losing a write to a crash, a
/// timeout, or a duplicate delivery.
///
/// Like ``MergeEngine`` this has no I/O of its own. Storage is ``LocalStore``, the network is
/// ``Transport``, and time is ``TimeSource``, so the whole engine runs with no simulator and
/// no server.
///
/// ## Ordering, and why it is this way round
///
/// Every round trip pushes before it pulls, and applies before it advances the watermark:
///
/// 1. **Push first.** The outbox is the only copy of a local edit the server has never seen.
///    Pulling first would be harmless for correctness — merge does not care — but it delays
///    the moment the user's work stops being single-homed on a phone.
/// 2. **Acknowledge on the server's word.** Ops are cleared from the outbox only when the
///    server names them in `appliedOpIds`, which includes replays of ops it had already
///    committed. A push whose response was lost is therefore resolved on the next attempt
///    rather than retried forever.
/// 3. **Observe every inbound clock before merging.** Skipping this lets the device later
///    stamp an edit below a timestamp it has already seen, so a fresh local edit silently
///    loses to a stale remote one.
/// 4. **Apply, then advance.** Entities and the watermark move together, atomically
///    (``LocalStore/applyRemote(merged:nextSeq:clock:)``). The failure this rules out is
///    advancing past changes that were never stored, which is unrecoverable — the device
///    will never ask for that range again.
///
/// Nothing here retries or backs off. A failed round trip throws with the outbox and
/// watermark untouched, and *when* to try again is a scheduling policy that belongs to the
/// platform — a `BGAppRefreshTask` and a foreground refresh on iOS.
///
/// Run one ``sync()`` at a time per device; a second concurrent call throws rather than
/// interleaving two page loops over one watermark. Local mutations may be interleaved
/// freely — they take the clock's lock, and the worst case is an op landing in the outbox
/// one round trip later than it might have.
public final class SyncEngine: @unchecked Sendable {

    /// Ops per push. Matches the server's cap so a full batch is never rejected.
    public static let defaultPushBatchSize = 500

    /// Round trips per ``sync()`` call. Bounds the work one invocation can do so a device
    /// returning from a long offline stretch yields to the scheduler — and battery, and the
    /// user — instead of draining a backlog in one uninterruptible burst.
    public static let defaultMaxPagesPerSync = 20

    /// Conflicts retained per ``sync()`` call before only the count is kept.
    ///
    /// A device coming back from a fortnight offline can lose thousands of writes in one
    /// drain, and holding every report would put an unbounded, user-controlled list in memory
    /// on a phone. The cap is generous next to what any notice or debug screen can usefully
    /// display, and ``SyncOutcome/conflictsObserved`` still reports the true total so nothing
    /// is silently understated.
    public static let maxReportedConflicts = 200

    private let clock: HlcClock
    private let store: any LocalStore
    private let transport: any Transport
    private let mergeEngine: MergeEngine
    private let pushBatchSize: Int
    private let maxPagesPerSync: Int
    private let makeOpId: @Sendable () -> UUID

    private let stateLock = NSLock()
    private var syncing = false

    /// - Parameter makeOpId: source of op ids. Overridable so a test can replay a failing run
    ///   exactly; nothing in the protocol orders by op id — the server keys idempotency off
    ///   it in a set, and merge decides by HLC alone — so a substituted source changes what a
    ///   run is called, never what it does. A source that ever repeated a value would have
    ///   the server treat a fresh op as a replay and silently drop the write.
    public init(
        clock: HlcClock,
        store: any LocalStore,
        transport: any Transport,
        mergeEngine: MergeEngine = MergeEngine(),
        pushBatchSize: Int = SyncEngine.defaultPushBatchSize,
        maxPagesPerSync: Int = SyncEngine.defaultMaxPagesPerSync,
        makeOpId: @escaping @Sendable () -> UUID = { UUID() }
    ) {
        precondition(pushBatchSize >= 1, "pushBatchSize must be >= 1, got \(pushBatchSize)")
        precondition(maxPagesPerSync >= 1, "maxPagesPerSync must be >= 1, got \(maxPagesPerSync)")
        self.clock = clock
        self.store = store
        self.transport = transport
        self.mergeEngine = mergeEngine
        self.pushBatchSize = pushBatchSize
        self.maxPagesPerSync = maxPagesPerSync
        self.makeOpId = makeOpId
    }

    /// Builds an engine for `nodeId`, restoring the device's clock from the store.
    ///
    /// Use this at app launch in preference to constructing an ``HlcClock`` directly — a
    /// clock that starts from zero after every process death reissues timestamps it has
    /// already used. See ``HlcClock/restored(nodeId:timeSource:maxDriftMillis:previous:)``.
    public static func forDevice(
        nodeId: String,
        store: any LocalStore,
        transport: any Transport,
        timeSource: any TimeSource = SystemTimeSource()
    ) throws -> SyncEngine {
        let clock: HlcClock
        if let previous = try store.lastClock() {
            clock = try HlcClock.restored(
                nodeId: nodeId, timeSource: timeSource, previous: previous)
        } else {
            clock = HlcClock(nodeId: nodeId, timeSource: timeSource)
        }
        return SyncEngine(clock: clock, store: store, transport: transport)
    }

    public var nodeId: String { clock.nodeId }

    // MARK: - Local edits

    /// Writes fields on an entity, creating it if this device has not seen it.
    ///
    /// Applied locally at once — offline-first means the UI never waits on a network — and
    /// queued for the next sync. The returned change is informational; the caller does not
    /// need to hold on to it.
    @discardableResult
    public func upsert(
        _ type: EntityType, _ entityId: UUID, fields: [String: FieldValue]
    ) throws -> Change {
        guard !fields.isEmpty else {
            // An UPSERT with no fields would occupy a sequence number, cost a round trip and
            // change nothing anywhere. Catching it here beats debugging why the log is full
            // of no-ops.
            throw CodecError("An upsert must write at least one field")
        }
        return try applyLocally(
            Change.upsert(
                opId: makeOpId(), type: type, entityId: entityId, hlc: clock.tick(), fields: fields))
    }

    /// Convenience for the common single-field write.
    @discardableResult
    public func upsert(
        _ type: EntityType, _ entityId: UUID, field: String, value: FieldValue
    ) throws -> Change {
        try upsert(type, entityId, fields: [field: value])
    }

    /// Tombstones an entity. It stops being ``EntityRecord/visible`` immediately.
    @discardableResult
    public func delete(_ type: EntityType, _ entityId: UUID) throws -> Change {
        try applyLocally(
            Change.delete(opId: makeOpId(), type: type, entityId: entityId, hlc: clock.tick()))
    }

    /// Clears a tombstone — the user's explicit undo.
    @discardableResult
    public func restore(_ type: EntityType, _ entityId: UUID) throws -> Change {
        try applyLocally(
            Change.restore(opId: makeOpId(), type: type, entityId: entityId, hlc: clock.tick()))
    }

    private func applyLocally(_ op: Change) throws -> Change {
        let current = try store.load(op.key)
        // No conflict can arise here, so none is collected: clock.tick() returns a reading
        // strictly greater than everything this device has stamped or observed, so a local
        // edit outranks every register it could land on. Losses are a pull-side phenomenon.
        let merged = mergeEngine.merge(current, op).state
        // One atomic write: the entity the user can now see, and the op that will tell the
        // server about it. Splitting these is how an edit ends up visible on one device and
        // nowhere else, permanently.
        try store.applyLocal(merged: merged, op: op)
        return op
    }

    /// The entity as this device currently sees it.
    public func load(_ type: EntityType, _ entityId: UUID) throws -> EntityRecord? {
        try store.load(EntityKey(type: type, id: entityId))
    }

    /// Local ops not yet confirmed by the server.
    public func pendingOpCount() throws -> Int { try store.pendingOpCount() }

    // MARK: - Sync

    /// Runs a full sync: push the outbox, drain the server's backlog, merge everything.
    ///
    /// Loops until the server reports no more pages and the outbox is empty, or until
    /// `maxPagesPerSync` round trips have been made — whichever comes first. When it stops
    /// early, ``SyncOutcome/moreRemaining`` says so.
    ///
    /// - Throws: ``TransportError`` if a round trip failed; nothing was acknowledged or
    ///   advanced beyond what earlier pages already committed. ``ClockDriftError`` if a
    ///   peer's clock is implausibly far ahead — the page is discarded whole and the
    ///   watermark is left alone, so the changes are re-offered once the situation is
    ///   resolved. Absorbing the timestamp instead would let one broken device starve every
    ///   honest write on the account.
    @discardableResult
    public func sync() async throws -> SyncOutcome {
        try beginSync()
        defer { endSync() }

        var acknowledged = 0
        var applied = 0
        var pages = 0
        var resynced = false
        var conflicts = ConflictLog()

        while pages < maxPagesPerSync {
            let watermarkBefore = try store.watermark()
            let ops = try store.pendingOps(limit: pushBatchSize)

            let response = try await transport.exchange(
                SyncRequest(sinceSeq: watermarkBefore, ops: ops))
            pages += 1

            let ackedNow = try acknowledge(response, pushed: ops)
            acknowledged += ackedNow

            if response.resyncRequired {
                if resynced {
                    // Twice in one sync means resetting did not satisfy the server, and
                    // looping would wipe local state repeatedly while making no progress.
                    // Not retryable: this needs a human, not a backoff.
                    throw TransportError(
                        "Server demanded a resync again immediately after one completed "
                            + "(reason: \(response.resyncReason ?? "unspecified"))",
                        retryable: false)
                }
                // Entity state goes; the outbox stays. Those ops are this device's own
                // un-pushed work, and the server's retention policy is no reason to bin it.
                try store.resetForResync()
                resynced = true
                // Nothing this sync says about conflicts survives the wipe. Reports taken
                // before it describe registers that no longer exist; reports taken after it
                // are worse, because a bootstrap replays the entire retained log and every
                // overwrite in the account's history — months old, long since notified —
                // would be re-announced as news and would crowd out the bounded list. The
                // device cannot tell which of those it has already shown. State converges
                // either way; the notice is a courtesy, and a courtesy that cries wolf over
                // ancient history is worse than silence.
                conflicts.suppress()
                continue
            }

            let appliedNow = try applyPage(response, watermarkBefore: watermarkBefore, conflicts: &conflicts)
            applied += appliedNow

            let outboxDrained = try store.pendingOpCount() == 0
            if !response.hasMore && outboxDrained {
                return try outcome(acknowledged, applied, pages, resynced, more: false, conflicts)
            }

            // Progress guard. Without it, a server that reports hasMore but returns an
            // unchanging page, or one that never acknowledges a poison op, spins this loop
            // until maxPagesPerSync burning battery and bandwidth on every sync forever.
            if ackedNow == 0 && appliedNow == 0 {
                return try outcome(acknowledged, applied, pages, resynced, more: true, conflicts)
            }
        }

        return try outcome(acknowledged, applied, pages, resynced, more: true, conflicts)
    }

    private func outcome(
        _ acknowledged: Int, _ applied: Int, _ pages: Int, _ resynced: Bool,
        more: Bool, _ conflicts: ConflictLog
    ) throws -> SyncOutcome {
        SyncOutcome(
            opsAcknowledged: acknowledged,
            changesApplied: applied,
            pagesFetched: pages,
            watermark: try store.watermark(),
            resynced: resynced,
            moreRemaining: more,
            conflicts: conflicts.reported,
            conflictsObserved: conflicts.observed)
    }

    private func acknowledge(_ response: SyncResponse, pushed: [Change]) throws -> Int {
        guard !response.appliedOpIds.isEmpty else { return 0 }
        // Only clear ops this device actually sent in this request. A server naming an id we
        // never pushed is confused or talking about another device; dropping our outbox on
        // its say-so would lose the edit outright.
        let ours = pushed.map(\.opId).filter { response.appliedOpIds.contains($0) }
        if !ours.isEmpty {
            try store.acknowledgeOps(ours)
        }
        return ours.count
    }

    /// Merges one page and commits it with its watermark.
    ///
    /// Changes are folded per entity in memory first, so a page touching one entity fifty
    /// times costs one load and one write rather than fifty of each. Order within the page is
    /// irrelevant — merge is commutative — but folding in arrival order keeps the intermediate
    /// states meaningful when stepping through a debugger.
    private func applyPage(
        _ response: SyncResponse, watermarkBefore: Int64, conflicts: inout ConflictLog
    ) throws -> Int {
        guard !response.changes.isEmpty else { return 0 }
        guard response.nextSeq > watermarkBefore else {
            // The cursor must advance whenever a page is non-empty, or the next request asks
            // for the same range and the loop never terminates.
            throw TransportError(
                "Server returned \(response.changes.count) changes but nextSeq "
                    + "\(response.nextSeq) did not advance past \(watermarkBefore)",
                retryable: false)
        }

        // Insertion order is kept explicitly: Swift dictionaries have none, and the page's
        // records are written back in this order.
        var order: [EntityKey] = []
        var working: [EntityKey: EntityRecord?] = [:]
        // What each entity looked like before this page, kept so the page's net effect on
        // visibility can be judged at the end. Judging it per change would misreport a page
        // carrying a DELETE and a later RESTORE as a deletion.
        var before: [EntityKey: EntityRecord?] = [:]

        for sequenced in response.changes {
            let change = sequenced.change

            // Before the merge, always. This is what makes the clock causal rather than a
            // timestamp generator, and it throws rather than absorbing an absurd skew.
            try clock.observe(change.hlc)

            let key = change.key
            if working[key] == nil {
                let loaded = try store.load(key)
                working[key] = .some(loaded)
                before[key] = .some(loaded)
                order.append(key)
            }
            let current = working[key] ?? nil
            let result = mergeEngine.merge(current, change)
            for resolution in result.resolutions {
                if let conflict = Conflict.from(entity: key, resolution: resolution, nodeId: clock.nodeId) {
                    conflicts.add(conflict)
                }
            }
            working[key] = .some(result.state)
        }

        var merged: [EntityRecord] = []
        merged.reserveCapacity(order.count)
        for key in order {
            guard let state = working[key] ?? nil else { continue }
            reportIfHidden(key: key, before: before[key] ?? nil, after: state, conflicts: &conflicts)
            merged.append(state)
        }

        try store.applyRemote(merged: merged, nextSeq: response.nextSeq, clock: clock.peek())
        return response.changes.count
    }

    /// Reports the ADR-003 headline case: something the user could see, and had edited here,
    /// was deleted on another device.
    ///
    /// No register was contested — the tombstone landed on an unset lifecycle register and
    /// the field writes survive intact — so ``MergeEngine`` correctly saw nothing remarkable.
    /// The loss is one of visibility, and it is only detectable by comparing the entity
    /// either side of the page, which is why it is handled here rather than in merge.
    private func reportIfHidden(
        key: EntityKey, before: EntityRecord?, after: EntityRecord, conflicts: inout ConflictLog
    ) {
        guard let before, before.visible, !after.visible else { return }
        guard let deletedBy = after.lifecycleClock else { return }
        if deletedBy.nodeId == clock.nodeId {
            // This device's own delete, arriving back from the server. The user did it on
            // purpose and does not need telling.
            return
        }
        // Only the user's own work is worth a notice. An entity holding nothing but other
        // devices' writes has cost this user nothing they will recognise.
        if let ownWrite = Self.newestWrite(in: after, by: clock.nodeId) {
            conflicts.add(Conflict.hiddenByDelete(entity: key, deletedBy: deletedBy, hiddenWrite: ownWrite))
        }
    }

    private static func newestWrite(in record: EntityRecord, by nodeId: String) -> Hlc? {
        var newest: Hlc?
        for written in record.fieldClocks.values where written.nodeId == nodeId {
            if newest == nil || written.isAfter(newest!) {
                newest = written
            }
        }
        return newest
    }

    // MARK: - Reentrancy

    private func beginSync() throws {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard !syncing else {
            throw TransportError(
                "A sync is already running on this device; run one at a time", retryable: true)
        }
        syncing = true
    }

    private func endSync() {
        stateLock.lock()
        syncing = false
        stateLock.unlock()
    }

    /// Accumulates conflict reports across the pages of one sync, keeping a bounded list and
    /// an unbounded count.
    ///
    /// The count keeps going after the list stops, so a caller can distinguish "three things
    /// were lost" from "three of the two thousand things lost are listed here". A plain
    /// truncated list cannot express the second, and quietly implies the first.
    private struct ConflictLog {
        private(set) var reported: [Conflict] = []
        private(set) var observed = 0
        private var suppressed = false

        mutating func add(_ conflict: Conflict) {
            guard !suppressed else { return }
            observed += 1
            if reported.count < SyncEngine.maxReportedConflicts {
                reported.append(conflict)
            }
        }

        /// Drops what has been collected and declines everything for the rest of the sync.
        ///
        /// Latched rather than merely cleared: a resync is followed by a replay of the whole
        /// retained log in the same ``SyncEngine/sync()`` call, so clearing once and carrying
        /// on would simply refill the list with the account's entire conflict history.
        mutating func suppress() {
            reported.removeAll()
            observed = 0
            suppressed = true
        }
    }
}
