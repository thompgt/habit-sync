import Foundation
import HabitSyncCore

/// The app-facing facade: habits and completions on one side, the sync engine on the other.
///
/// Everything here is a thin projection over ``SyncEngine``. That is deliberate — the
/// temptation in an offline-first app is to keep a second, "convenient" model beside the
/// replicated one and reconcile the two, and every bug that follows is a reconciliation bug.
/// There is one durable representation (per-field registers in ``LocalStore``), one way to
/// change it (an op through the engine), and reads are recomputed from it.
///
/// Every mutation lands on disk before it returns and touches no network. The network happens
/// later, in ``sync()``, and may be days later.
public final class HabitSyncService: @unchecked Sendable {

    public let session: Session
    public let engine: SyncEngine
    private let store: any LocalStore & RecordQuerying

    public init(session: Session, store: any LocalStore & RecordQuerying, transport: any Transport) throws {
        self.session = session
        self.store = store
        // forDevice restores the clock from the store. A device that started from zero on
        // every launch would reissue timestamps it had already used, and its fresh edits would
        // lose to its own stale ones.
        self.engine = try SyncEngine.forDevice(
            nodeId: session.nodeId, store: store, transport: transport)
    }

    // MARK: - Reads

    /// Habits as this device currently sees them, tombstones excluded, name-ordered.
    public func habits(includeDeleted: Bool = false) throws -> [Habit] {
        try store.records(ofType: .habit)
            .filter { includeDeleted || $0.visible }
            .compactMap(Habit.init(record:))
            .sorted { ($0.name.localizedStandardCompare($1.name)) == .orderedAscending }
    }

    public func habit(_ id: UUID) throws -> Habit? {
        try store.load(EntityKey(type: .habit, id: id)).flatMap(Habit.init(record:))
    }

    /// Completions this device holds, newest first, tombstones excluded.
    public func completions() throws -> [Completion] {
        try store.records(ofType: .habitCompletion)
            .filter(\.visible)
            .compactMap(Completion.init(record:))
            .sorted { $0.at > $1.at }
    }

    /// Watermark, outbox depth and clock — the same three numbers the CLI's `status` prints,
    /// and the first three questions worth asking when a device looks out of date.
    public func status() throws -> DeviceStatus {
        DeviceStatus(
            deviceId: session.deviceId,
            watermark: try store.watermark(),
            pendingOps: try store.pendingOpCount(),
            clock: try store.lastClock(),
            entityCount: try store.allRecords().count)
    }

    // MARK: - Writes

    /// Creates a habit. Returns its id so the caller can navigate straight to it.
    @discardableResult
    public func addHabit(name: String, weeklyTarget: Int? = nil, colour: String? = nil) throws -> UUID {
        var fields: [String: FieldValue] = [HabitField.name: .of(name)]
        if let weeklyTarget { fields[HabitField.weeklyTarget] = .of(Int64(weeklyTarget)) }
        if let colour { fields[HabitField.colour] = .of(colour) }

        let id = UUID()
        try engine.upsert(.habit, id, fields: fields)
        return id
    }

    /// Renames a habit.
    ///
    /// One field per op, always. A rename here and a target change on another device touch
    /// disjoint registers and both survive; bundling every field into one op would make the
    /// later of the two overwrite fields its user never touched.
    public func rename(_ id: UUID, to name: String) throws {
        try engine.upsert(.habit, id, field: HabitField.name, value: .of(name))
    }

    public func setWeeklyTarget(_ id: UUID, to target: Int?) throws {
        try engine.upsert(
            .habit, id, field: HabitField.weeklyTarget,
            value: target.map { FieldValue.of(Int64($0)) } ?? .null)
    }

    public func setColour(_ id: UUID, to colour: String?) throws {
        try engine.upsert(
            .habit, id, field: HabitField.colour, value: colour.map { FieldValue.of($0) } ?? .null)
    }

    /// Writes one field by name, with `nil` meaning "clear it".
    ///
    /// The clear is not a convenience. A cleared field and a never-written one are different
    /// states everywhere in this system, and a UI that could only ever write non-null values
    /// would leave that distinction exercised by nothing but tests — which is how it comes to
    /// be broken by an innocent-looking change to serialisation.
    public func setField(_ id: UUID, field: String, raw: String?) throws {
        try engine.upsert(.habit, id, field: field, value: FieldValue(raw))
    }

    /// Tombstones a habit. It disappears from ``habits(includeDeleted:)`` at once.
    public func delete(_ id: UUID) throws {
        try engine.delete(.habit, id)
    }

    /// Clears a tombstone — the user's explicit undo.
    ///
    /// Only this brings an entity back. A tombstone is never lifted as a side effect of a
    /// later field edit arriving, which is what makes deletion terminal without making it
    /// irreversible.
    public func restore(_ id: UUID) throws {
        try engine.restore(.habit, id)
    }

    /// Logs a completion of `habitId` at `date`.
    ///
    /// An append-only fact: created once, never edited, and genuinely incapable of conflicting
    /// with anything. Two devices logging the same habit offline produce two entities with
    /// different client-generated ids, and both survive — which is the right answer, because
    /// the alternative is deciding on the user's behalf that they only did it once.
    @discardableResult
    public func logCompletion(of habitId: UUID, at date: Date = Date()) throws -> UUID {
        let id = UUID()
        try engine.upsert(
            .habitCompletion, id,
            fields: [
                CompletionField.habitId: .of(habitId),
                CompletionField.at: .of(date),
            ])
        return id
    }

    /// Removes a completion logged by mistake.
    ///
    /// A tombstone rather than a real delete, like everything else here: a second device that
    /// has not seen the removal yet must learn about it, and an entity that simply vanished
    /// from this device would be re-created by the next page that mentions it.
    public func removeCompletion(_ id: UUID) throws {
        try engine.delete(.habitCompletion, id)
    }

    // MARK: - Sync

    /// Pushes the outbox and pulls what this device missed.
    ///
    /// Throwing is the normal case on a phone, not an exceptional one — see
    /// ``TransportError/retryable``, which is the difference between "we are in a tunnel" and
    /// "these credentials are gone".
    @discardableResult
    public func sync() async throws -> SyncOutcome {
        try await engine.sync()
    }
}

/// The three numbers worth showing on a debug screen, plus the identity they belong to.
public struct DeviceStatus: Hashable, Sendable {
    /// This device's id — and its HLC node id, which is why it appears in every timestamp.
    public let deviceId: UUID
    /// The highest server sequence durably applied here.
    public let watermark: Int64
    /// Local ops the server has not yet confirmed. Non-zero after an offline stretch, and the
    /// number that should go to zero after a successful sync.
    public let pendingOps: Int
    /// The highest clock reading this device has committed.
    public let clock: Hlc?
    public let entityCount: Int
}
