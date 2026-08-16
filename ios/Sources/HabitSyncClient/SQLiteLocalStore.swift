import Foundation
import HabitSyncCore
import SQLite3

/// A durable ``LocalStore`` on SQLite — the device's disk.
///
/// ``InMemoryLocalStore`` satisfies the same protocol and is what the tests use, but it
/// satisfies the atomicity contract by holding a lock, which is exactly as strong as a single
/// process and no stronger. The contract is about surviving process death, and only a
/// transaction on disk provides that. This type is where that claim is actually cashed.
///
/// ## The two transactions that matter
///
/// - ``applyLocal(merged:op:)`` writes the merged entity **and** enqueues the outbox op in
///   one commit. Split them, and a crash in between leaves the user's edit on screen and
///   nowhere else — silent data loss, discovered weeks later when a second device never shows
///   it.
/// - ``applyRemote(merged:nextSeq:clock:)`` writes the page's entities **and** advances the
///   watermark in one commit. Advancing first would let a crash skip that range permanently,
///   because the device never asks for it again.
///
/// `synchronous=FULL`, not SQLite's default of NORMAL. NORMAL under WAL can lose the tail of
/// recently committed transactions on power loss, which is precisely the failure this type
/// exists to prevent — and the write rate here is a handful of transactions per sync, so the
/// cost is irrelevant.
///
/// ## Why the clock is stored here and not saved separately
///
/// ``lastClock()`` is derived from the writes this store already accepts, in the same
/// transaction as the change that used it. A separate `saveClock` call could land on the other
/// side of a crash from the op it stamped, and the device would then reissue an HLC it had
/// already used — two different writes with identical timestamps, which is the one way a
/// client can break convergence, because merge's strictly-greater rule leaves replicas that
/// see them in different orders free to pick different winners.
public final class SQLiteLocalStore: LocalStore, RecordQuerying, @unchecked Sendable {

    private static let keyWatermark = "watermark"
    private static let keyLastClock = "lastClock"

    /// SQLite's own destructor sentinel: "this buffer will not outlive the call, copy it".
    /// Binding Swift strings without it hands SQLite a pointer that dies at the end of the
    /// statement — the kind of bug that reads back as intermittently corrupt text.
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private let lock = NSLock()
    private var db: OpaquePointer?

    public enum StoreError: Error, CustomStringConvertible {
        case open(path: String, message: String)
        case sqlite(String)
        case corrupt(String)

        public var description: String {
            switch self {
            case .open(let path, let message): return "Could not open the local store at \(path): \(message)"
            case .sqlite(let message): return message
            case .corrupt(let message): return "Local store holds unreadable data: \(message)"
            }
        }
    }

    /// Opens (or creates) the database at `path`.
    ///
    /// - Parameter path: a filesystem path, or `":memory:"` for a throwaway database.
    public init(path: String) throws {
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &handle, flags, nil) == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close_v2(handle)
            throw StoreError.open(path: path, message: message)
        }
        db = handle
        // Ten seconds of busy-waiting before giving up. The only other writer is this app's
        // own background sync task, and a lock it holds is measured in milliseconds; the
        // timeout exists so a rare overlap waits instead of failing the user's edit.
        sqlite3_busy_timeout(handle, 10_000)
        do {
            try initialise()
        } catch {
            sqlite3_close_v2(handle)
            db = nil
            throw error
        }
    }

    /// The store in the app's Application Support directory, created if needed.
    ///
    /// Application Support rather than Documents: this is app-managed state, not user
    /// documents, and it should not appear in the Files app. It *is* backed up, deliberately —
    /// the outbox may hold the only copy of an edit made offline, and excluding it from backup
    /// would make a device restore lose exactly the work that had not reached the server yet.
    public static func defaultLocation(fileName: String = "habits.sqlite3") throws -> SQLiteLocalStore {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let folder = directory.appendingPathComponent("HabitSync", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return try SQLiteLocalStore(path: folder.appendingPathComponent(fileName).path)
    }

    /// An in-memory database, for tests. Durability is absent by definition; atomicity is not.
    public static func inMemory() throws -> SQLiteLocalStore {
        try SQLiteLocalStore(path: ":memory:")
    }

    deinit {
        sqlite3_close_v2(db)
    }

    private func initialise() throws {
        // Pragmas and DDL first. WAL and the safety level cannot be changed inside a
        // transaction, so this runs before anything opens one.
        try exec("PRAGMA journal_mode=WAL")
        try exec("PRAGMA synchronous=FULL")
        try exec("PRAGMA foreign_keys=ON")
        try exec("""
            CREATE TABLE IF NOT EXISTS entity (
                entity_type   TEXT NOT NULL,
                entity_id     TEXT NOT NULL,
                deleted       INTEGER NOT NULL DEFAULT 0,
                lifecycle_hlc TEXT,
                PRIMARY KEY (entity_type, entity_id)
            )
            """)
        // One row per field, mirroring the server's entity_field and the core's per-field
        // registers. A NULL value is a cleared field and is distinct from no row at all.
        try exec("""
            CREATE TABLE IF NOT EXISTS entity_field (
                entity_type TEXT NOT NULL,
                entity_id   TEXT NOT NULL,
                field       TEXT NOT NULL,
                value       TEXT,
                hlc         TEXT NOT NULL,
                PRIMARY KEY (entity_type, entity_id, field),
                FOREIGN KEY (entity_type, entity_id)
                    REFERENCES entity (entity_type, entity_id) ON DELETE CASCADE
            )
            """)
        // seq gives the outbox a stable oldest-first order. Ordering is a courtesy to the
        // server's logs rather than a correctness requirement — merge is commutative — but an
        // unordered outbox makes a device's own history unreadable when debugging.
        try exec("""
            CREATE TABLE IF NOT EXISTS outbox (
                seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                op_id   TEXT NOT NULL UNIQUE,
                payload TEXT NOT NULL
            )
            """)
        try exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    // MARK: - Reads

    public func load(_ key: EntityKey) throws -> EntityRecord? {
        lock.lock()
        defer { lock.unlock() }
        return try loadWithin(key)
    }

    private func loadWithin(_ key: EntityKey) throws -> EntityRecord? {
        var deleted = false
        var lifecycle: Hlc?

        let head = try prepare(
            "SELECT deleted, lifecycle_hlc FROM entity WHERE entity_type = ? AND entity_id = ?")
        defer { sqlite3_finalize(head) }
        bind(head, 1, key.type.rawValue)
        bind(head, 2, Self.text(key.id))
        switch sqlite3_step(head) {
        case SQLITE_ROW:
            deleted = sqlite3_column_int(head, 0) != 0
            if let raw = column(head, 1) {
                lifecycle = try Hlc.parse(raw)
            }
        case SQLITE_DONE:
            return nil
        default:
            throw lastError()
        }

        var fields: [String: FieldValue] = [:]
        var clocks: [String: Hlc] = [:]
        let rows = try prepare(
            "SELECT field, value, hlc FROM entity_field WHERE entity_type = ? AND entity_id = ? ORDER BY field")
        defer { sqlite3_finalize(rows) }
        bind(rows, 1, key.type.rawValue)
        bind(rows, 2, Self.text(key.id))
        while true {
            let step = sqlite3_step(rows)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else { throw lastError() }
            guard let name = column(rows, 0), let hlcText = column(rows, 2) else {
                throw StoreError.corrupt("A field row is missing its name or clock")
            }
            // column() returns nil for SQL NULL, which is the cleared-field representation —
            // and is distinct from the row being absent entirely.
            fields[name] = FieldValue(column(rows, 1))
            clocks[name] = try Hlc.parse(hlcText)
        }

        return EntityRecord(
            type: key.type, id: key.id, fields: fields, fieldClocks: clocks,
            deleted: deleted, lifecycleClock: lifecycle)
    }

    public func watermark() throws -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return try watermarkWithin()
    }

    private func watermarkWithin() throws -> Int64 {
        Int64(try meta(Self.keyWatermark) ?? "0") ?? 0
    }

    public func lastClock() throws -> Hlc? {
        lock.lock()
        defer { lock.unlock() }
        guard let raw = try meta(Self.keyLastClock) else { return nil }
        return try Hlc.parse(raw)
    }

    public func pendingOps(limit: Int) throws -> [Change] {
        guard limit >= 0 else { throw StoreError.sqlite("limit must be >= 0, got \(limit)") }
        lock.lock()
        defer { lock.unlock() }

        let statement = try prepare("SELECT payload FROM outbox ORDER BY seq LIMIT ?")
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_int(statement, 1, Int32(limit))

        var ops: [Change] = []
        let decoder = JSONDecoder()
        while true {
            let step = sqlite3_step(statement)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else { throw lastError() }
            guard let payload = column(statement, 0), let data = payload.data(using: .utf8) else {
                throw StoreError.corrupt("An outbox row holds an unreadable payload")
            }
            ops.append(try ChangeCodec.decode(try decoder.decode(WireChange.self, from: data)))
        }
        return ops
    }

    public func pendingOpCount() throws -> Int {
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare("SELECT count(*) FROM outbox")
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else { throw lastError() }
        return Int(sqlite3_column_int64(statement, 0))
    }

    // MARK: - Writes

    public func applyLocal(merged: EntityRecord, op: Change) throws {
        guard merged.key == op.key else {
            throw StoreError.sqlite("Record \(merged.key) does not match op target \(op.key)")
        }
        lock.lock()
        defer { lock.unlock() }
        try inTransaction {
            try writeEntity(merged)
            try enqueue(op)
            // Derived from the op, in the same commit, so the clock cannot outlive or trail
            // the change that used it.
            try advanceClock(op.hlc)
        }
    }

    public func applyRemote(merged: [EntityRecord], nextSeq: Int64, clock: Hlc) throws {
        lock.lock()
        defer { lock.unlock() }
        let current = try watermarkWithin()
        guard nextSeq >= current else {
            throw StoreError.sqlite("Watermark must not go backwards: \(current) -> \(nextSeq)")
        }
        try inTransaction {
            for record in merged {
                try writeEntity(record)
            }
            // Same commit as the entities above. This is the ordering LocalStore's contract
            // exists for: a watermark ahead of the state it describes is unrecoverable.
            try putMeta(Self.keyWatermark, String(nextSeq))
            try advanceClock(clock)
        }
    }

    public func acknowledgeOps(_ opIds: [UUID]) throws {
        guard !opIds.isEmpty else { return }
        lock.lock()
        defer { lock.unlock() }
        try inTransaction {
            let statement = try prepare("DELETE FROM outbox WHERE op_id = ?")
            defer { sqlite3_finalize(statement) }
            for opId in opIds {
                sqlite3_reset(statement)
                sqlite3_clear_bindings(statement)
                bind(statement, 1, Self.text(opId))
                guard sqlite3_step(statement) == SQLITE_DONE else { throw lastError() }
            }
        }
    }

    public func resetForResync() throws {
        lock.lock()
        defer { lock.unlock() }
        try inTransaction {
            try execWithin("DELETE FROM entity_field")
            try execWithin("DELETE FROM entity")
            try putMeta(Self.keyWatermark, "0")
            // The outbox and lastClock deliberately survive. Those ops are the device's own
            // un-pushed work and the server's retention policy is no reason to bin them; the
            // clock is this device's monotonic identity, and resetting it would let the device
            // reissue timestamps it has already used.
        }
    }

    // MARK: - Bulk reads

    public func allRecords() throws -> [EntityRecord] {
        lock.lock()
        defer { lock.unlock() }
        return try recordsWithin(ofType: nil)
    }

    public func records(ofType type: EntityType) throws -> [EntityRecord] {
        lock.lock()
        defer { lock.unlock() }
        return try recordsWithin(ofType: type)
    }

    private func recordsWithin(ofType type: EntityType?) throws -> [EntityRecord] {
        let sql = type == nil
            ? "SELECT entity_type, entity_id FROM entity ORDER BY entity_type, entity_id"
            : "SELECT entity_type, entity_id FROM entity WHERE entity_type = ? ORDER BY entity_id"
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        if let type {
            bind(statement, 1, type.rawValue)
        }

        var keys: [EntityKey] = []
        while true {
            let step = sqlite3_step(statement)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else { throw lastError() }
            guard let rawType = column(statement, 0), let rawId = column(statement, 1),
                let entityType = EntityType(rawValue: rawType), let id = UUID(uuidString: rawId)
            else {
                // A row written by a newer version that knows an entity type this build does
                // not. Skipping it is correct: the data is intact on disk and will be readable
                // after an upgrade, whereas failing the whole query would take the habit list
                // down with it.
                continue
            }
            keys.append(EntityKey(type: entityType, id: id))
        }

        return try keys.compactMap { try loadWithin($0) }
    }

    // MARK: - Plumbing

    private func writeEntity(_ record: EntityRecord) throws {
        let head = try prepare("""
            INSERT INTO entity (entity_type, entity_id, deleted, lifecycle_hlc)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (entity_type, entity_id)
            DO UPDATE SET deleted = excluded.deleted, lifecycle_hlc = excluded.lifecycle_hlc
            """)
        defer { sqlite3_finalize(head) }
        bind(head, 1, record.type.rawValue)
        bind(head, 2, Self.text(record.id))
        sqlite3_bind_int(head, 3, record.deleted ? 1 : 0)
        bind(head, 4, record.lifecycleClock?.compactString)
        guard sqlite3_step(head) == SQLITE_DONE else { throw lastError() }

        guard !record.fields.isEmpty else { return }
        let rows = try prepare("""
            INSERT INTO entity_field (entity_type, entity_id, field, value, hlc)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (entity_type, entity_id, field)
            DO UPDATE SET value = excluded.value, hlc = excluded.hlc
            """)
        defer { sqlite3_finalize(rows) }
        for (name, value) in record.fields {
            guard let clock = record.clock(of: name) else {
                throw StoreError.corrupt("Field '\(name)' on \(record.key) has no clock")
            }
            sqlite3_reset(rows)
            sqlite3_clear_bindings(rows)
            bind(rows, 1, record.type.rawValue)
            bind(rows, 2, Self.text(record.id))
            bind(rows, 3, name)
            // bind() writes SQL NULL for a nil value, which is the cleared-field
            // representation — not the absence of the row.
            bind(rows, 4, value.raw)
            bind(rows, 5, clock.compactString)
            guard sqlite3_step(rows) == SQLITE_DONE else { throw lastError() }
        }
    }

    private func enqueue(_ op: Change) throws {
        let statement = try prepare("INSERT OR IGNORE INTO outbox (op_id, payload) VALUES (?, ?)")
        defer { sqlite3_finalize(statement) }
        let payload = try JSONEncoder().encode(ChangeCodec.encode(op))
        guard let text = String(data: payload, encoding: .utf8) else {
            throw StoreError.corrupt("Op \(op.opId) did not encode to UTF-8")
        }
        bind(statement, 1, Self.text(op.opId))
        bind(statement, 2, text)
        guard sqlite3_step(statement) == SQLITE_DONE else { throw lastError() }
    }

    /// Keeps `lastClock` at the highest reading this store has ever committed.
    private func advanceClock(_ observed: Hlc) throws {
        let previous = try meta(Self.keyLastClock).map { try Hlc.parse($0) }
        guard let next = Hlc.max(previous, observed) else { return }
        try putMeta(Self.keyLastClock, next.compactString)
    }

    private func meta(_ key: String) throws -> String? {
        let statement = try prepare("SELECT value FROM meta WHERE key = ?")
        defer { sqlite3_finalize(statement) }
        bind(statement, 1, key)
        switch sqlite3_step(statement) {
        case SQLITE_ROW: return column(statement, 0)
        case SQLITE_DONE: return nil
        default: throw lastError()
        }
    }

    private func putMeta(_ key: String, _ value: String) throws {
        let statement = try prepare(
            "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")
        defer { sqlite3_finalize(statement) }
        bind(statement, 1, key)
        bind(statement, 2, value)
        guard sqlite3_step(statement) == SQLITE_DONE else { throw lastError() }
    }

    /// Runs `body` in one transaction, rolling back on any failure.
    ///
    /// The rollback is the point. A half-applied ``applyLocal(merged:op:)`` is the corruption
    /// this type exists to rule out, and "we would have noticed" is not a mechanism.
    ///
    /// `BEGIN IMMEDIATE` rather than the default deferred begin: it takes the write lock up
    /// front, so contention with the sync task surfaces as a wait here instead of as a
    /// `SQLITE_BUSY` thrown from the middle of a half-written transaction.
    private func inTransaction(_ body: () throws -> Void) throws {
        try execWithin("BEGIN IMMEDIATE")
        do {
            try body()
            try execWithin("COMMIT")
        } catch {
            try? execWithin("ROLLBACK")
            throw error
        }
    }

    private func exec(_ sql: String) throws {
        lock.lock()
        defer { lock.unlock() }
        try execWithin(sql)
    }

    private func execWithin(_ sql: String) throws {
        var message: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &message) == SQLITE_OK else {
            let text = message.map { String(cString: $0) } ?? "unknown error"
            sqlite3_free(message)
            throw StoreError.sqlite("\(text) (while running: \(sql.prefix(120)))")
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else {
            throw lastError()
        }
        return statement
    }

    private func bind(_ statement: OpaquePointer?, _ index: Int32, _ value: String?) {
        if let value {
            sqlite3_bind_text(statement, index, value, -1, Self.transient)
        } else {
            sqlite3_bind_null(statement, index)
        }
    }

    private func column(_ statement: OpaquePointer?, _ index: Int32) -> String? {
        guard let raw = sqlite3_column_text(statement, index) else { return nil }
        return String(cString: raw)
    }

    private func lastError() -> StoreError {
        StoreError.sqlite(String(cString: sqlite3_errmsg(db)))
    }

    /// UUIDs are stored lowercase, matching what the JVM client writes and what the server
    /// returns. Foundation's `uuidString` is uppercase, so leaving it alone would make a
    /// database written here unreadable by the same-account tooling on the other side.
    private static func text(_ id: UUID) -> String {
        id.uuidString.lowercased()
    }
}
