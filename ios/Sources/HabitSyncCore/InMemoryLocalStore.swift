import Foundation

/// A ``LocalStore`` that keeps everything in memory, for tests.
///
/// It satisfies the atomicity contract by holding a lock, which is exactly as strong as a
/// single process and no stronger. The contract is really about surviving process death, and
/// only a transaction on disk provides that — see `SQLiteLocalStore` in `HabitSyncClient`,
/// which is where that claim is actually cashed. This type exists so the engine's behaviour
/// can be exercised without a filesystem.
public final class InMemoryLocalStore: LocalStore, RecordQuerying, @unchecked Sendable {

    private let lock = NSLock()
    private var records: [EntityKey: EntityRecord] = [:]
    /// Insertion-ordered, because the outbox's oldest-first contract is observable.
    private var outbox: [Change] = []
    private var storedWatermark: Int64 = 0
    private var storedClock: Hlc?

    public init() {}

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    public func load(_ key: EntityKey) throws -> EntityRecord? {
        locked { records[key] }
    }

    public func applyLocal(merged: EntityRecord, op: Change) throws {
        guard merged.key == op.key else {
            throw CodecError("Record \(merged.key) does not match op target \(op.key)")
        }
        locked {
            records[merged.key] = merged
            // INSERT OR IGNORE semantics: re-enqueuing an op already in the outbox must not
            // duplicate it, which is what the SQLite store's unique index on op_id gives.
            if !outbox.contains(where: { $0.opId == op.opId }) {
                outbox.append(op)
            }
            advanceClock(op.hlc)
        }
    }

    public func applyRemote(merged: [EntityRecord], nextSeq: Int64, clock: Hlc) throws {
        try locked {
            guard nextSeq >= storedWatermark else {
                throw CodecError("Watermark must not go backwards: \(storedWatermark) -> \(nextSeq)")
            }
            for record in merged {
                records[record.key] = record
            }
            storedWatermark = nextSeq
            advanceClock(clock)
        }
    }

    public func watermark() throws -> Int64 { locked { storedWatermark } }

    public func pendingOps(limit: Int) throws -> [Change] {
        guard limit >= 0 else { throw CodecError("limit must be >= 0, got \(limit)") }
        return locked { Array(outbox.prefix(limit)) }
    }

    public func pendingOpCount() throws -> Int { locked { outbox.count } }

    public func acknowledgeOps(_ opIds: [UUID]) throws {
        let acknowledged = Set(opIds)
        locked { outbox.removeAll { acknowledged.contains($0.opId) } }
    }

    public func resetForResync() throws {
        locked {
            records.removeAll()
            storedWatermark = 0
            // The outbox and the clock deliberately survive. Those ops are the device's own
            // un-pushed work, and the clock is its monotonic identity — resetting it would
            // let the device reissue timestamps it has already used.
        }
    }

    public func lastClock() throws -> Hlc? { locked { storedClock } }

    public func allRecords() throws -> [EntityRecord] {
        locked { records.values.sorted { $0.id.uuidString < $1.id.uuidString } }
    }

    public func records(ofType type: EntityType) throws -> [EntityRecord] {
        try allRecords().filter { $0.type == type }
    }

    /// Keeps `lastClock` at the highest reading this store has ever committed.
    private func advanceClock(_ observed: Hlc) {
        storedClock = Hlc.max(storedClock, observed)
    }
}
