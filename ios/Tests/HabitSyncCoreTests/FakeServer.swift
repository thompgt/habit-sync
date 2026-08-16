import Foundation

@testable import HabitSyncCore

/// An in-process stand-in for `/v1/sync`: a replication log, an op-id set for idempotency, and
/// merged entity state, driven by the same ``MergeEngine`` the real server runs.
///
/// A port of the JVM test double, and mirroring the server's *observable* behaviour rather
/// than reimplementing its internals — sequence allocation in arrival order, replayed ops
/// acknowledged without being re-logged, pages bounded and cursored by `nextSeq`.
///
/// The failure knobs are the point of having it: a transport that drops the next response, a
/// server that demands a resync, one that acknowledges ops it was never sent. Those are the
/// paths that are near-impossible to provoke against a real server and are exactly where
/// offline-first clients lose data.
final class FakeServer: Transport, @unchecked Sendable {

    private let engine = MergeEngine()
    private let lock = NSLock()

    private var log: [SequencedChange] = []
    private var committedOpIds: Set<UUID> = []
    private var state: [EntityKey: EntityRecord] = [:]

    var pageSize = 100
    private(set) var exchanges = 0

    // Knobs
    var failNextExchanges = 0
    var retryableFailures = true
    var demandResyncTimes = 0
    var duplicateEveryPage = false
    var phantomAcks: Set<UUID> = []
    var freezeCursor = false

    var logSize: Int {
        lock.lock()
        defer { lock.unlock() }
        return log.count
    }

    func exchange(_ request: SyncRequest) async throws -> SyncResponse {
        lock.lock()
        defer { lock.unlock() }

        exchanges += 1

        if failNextExchanges > 0 {
            failNextExchanges -= 1
            throw TransportError("simulated network failure", retryable: retryableFailures)
        }

        var applied = commitLocked(request.ops)
        applied.formUnion(phantomAcks)

        if demandResyncTimes > 0 {
            demandResyncTimes -= 1
            // Note the acks still ride along: a resync directive must not cost the client the
            // ops the server just committed, or it would push them all over again.
            return try SyncResponse(
                appliedOpIds: applied, changes: [], nextSeq: 0, hasMore: false,
                resyncRequired: true, resyncReason: "watermarkBelowGcHorizon", serverTimeMillis: 0)
        }

        var page: [SequencedChange] = []
        for entry in log where entry.serverSeq > request.sinceSeq {
            page.append(entry)
            if duplicateEveryPage { page.append(entry) }
            if page.count >= pageSize { break }
        }

        let highestServed = page.map(\.serverSeq).max() ?? request.sinceSeq
        let hasMore = (log.last?.serverSeq ?? 0) > highestServed

        return try SyncResponse(
            appliedOpIds: applied,
            changes: page,
            nextSeq: freezeCursor ? request.sinceSeq : highestServed,
            hasMore: hasMore || freezeCursor,
            resyncRequired: false,
            resyncReason: nil,
            serverTimeMillis: 0)
    }

    /// Appends fresh ops to the log and merges them; replays are acknowledged only.
    private func commitLocked(_ ops: [Change]) -> Set<UUID> {
        var acknowledged: Set<UUID> = []
        for op in ops {
            acknowledged.insert(op.opId)
            guard committedOpIds.insert(op.opId).inserted else { continue }
            log.append(SequencedChange(serverSeq: Int64(log.count + 1), change: op))
            state[op.key] = engine.merge(state[op.key], op).state
        }
        return acknowledged
    }

    /// Injects a change as if another device had pushed it.
    func receive(_ op: Change) {
        lock.lock()
        defer { lock.unlock() }
        _ = commitLocked([op])
    }

    func serverState(_ key: EntityKey) -> EntityRecord? {
        lock.lock()
        defer { lock.unlock() }
        return state[key]
    }
}
