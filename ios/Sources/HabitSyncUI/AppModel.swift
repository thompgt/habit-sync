import Foundation
import HabitSyncClient
import HabitSyncCore
import Observation

/// The one piece of state the screens share: who is signed in, what this device holds, and
/// what the last sync did.
///
/// Main-actor isolated, and the local writes it performs run inline rather than being hopped
/// onto a background queue. That is a considered choice: a local edit is one small SQLite
/// transaction, the user is waiting for it, and doing it inline means the list is correct the
/// instant the method returns — no optimistic copy to reconcile, no window where the screen
/// and the database disagree. The only work moved off the main actor is ``sync()``, which
/// talks to the network and can take as long as it likes.
@MainActor
@Observable
public final class AppModel {

    /// Which screen the app should be showing.
    public enum Phase: Equatable {
        /// Reading the Keychain and opening the database.
        case starting
        /// No session on this device, or the user signed out.
        case signedOut
        case signedIn
        /// The local store could not be opened at all — a full disk, or a database this build
        /// cannot read. Nothing works until it is resolved, so it gets its own screen rather
        /// than an alert over an empty list.
        case unusable(String)
    }

    public private(set) var phase: Phase = .starting
    public private(set) var habits: [Habit] = []
    public private(set) var completions: [Completion] = []
    public private(set) var status: DeviceStatus?
    public private(set) var isSyncing = false
    /// The last sync's report, kept so the status screen can show what it did.
    public private(set) var lastOutcome: SyncOutcome?
    public private(set) var lastSyncedAt: Date?
    /// Losses from the most recent sync that cost work done on *this* device. Cleared when the
    /// user acknowledges them — they are a notice, not a queue.
    public private(set) var pendingNotices: [Conflict] = []
    /// The last failure worth showing. Retryable failures are the ordinary offline case and
    /// are recorded quietly; non-retryable ones are what an alert is for.
    public private(set) var lastError: SyncFailure?

    /// Where the server lives. Defaults to the simulator's view of a Mac running the server,
    /// which is what `docker compose up` on the same machine gives you.
    public var serverURL: String {
        didSet { UserDefaults.standard.set(serverURL, forKey: Self.serverURLKey) }
    }

    /// How many engine calls one user-visible sync will chain. Twenty pages each means a
    /// hundred round trips, which is far more backlog than a phone accumulates in a fortnight.
    private static let maxDrainsPerSync = 5

    private static let serverURLKey = "dev.thompgt.habitsync.serverURL"
    private static let defaultServerURL = "http://localhost:8080"

    private let sessions = KeychainSessionStore()
    private var store: (any LocalStore & RecordQuerying)?
    private var service: HabitSyncService?

    public init() {
        serverURL = UserDefaults.standard.string(forKey: Self.serverURLKey) ?? Self.defaultServerURL
    }

    /// A failure worth putting in front of someone, with the distinction that decides how.
    public struct SyncFailure: Identifiable, Equatable {
        public let id = UUID()
        public let message: String
        /// Retrying later could plausibly work without the user doing anything.
        public let retryable: Bool
        public let at: Date
    }

    public var deviceStatus: DeviceStatus? { status }

    /// This device's node id, shown wherever a conflict names one — otherwise "who won" is a
    /// pair of opaque UUIDs.
    public var nodeId: String? { service?.session.nodeId }

    // MARK: - Lifecycle

    /// Opens the database and restores the session. Safe to call more than once.
    public func start() async {
        guard case .starting = phase else { return }
        do {
            let opened = try SQLiteLocalStore.defaultLocation()
            store = opened
            guard let session = try sessions.load(), !session.refreshToken.isEmpty else {
                phase = .signedOut
                return
            }
            try attach(session: session, store: opened)
        } catch {
            phase = .unusable(String(describing: error))
        }
    }

    private func attach(session: Session, store: any LocalStore & RecordQuerying) throws {
        let transport = HTTPTransport(baseURL: serverURL, session: session)
        service = try HabitSyncService(session: session, store: store, transport: transport)
        phase = .signedIn
        refresh()
    }

    // MARK: - Authentication

    public func signIn(email: String, password: String, deviceName: String, register: Bool) async {
        guard let store else {
            phase = .unusable("The local database is not open")
            return
        }
        do {
            let session: Session
            if register {
                session = try await HTTPTransport.register(
                    baseURL: serverURL, email: email, password: password, deviceName: deviceName)
            } else {
                // Reuse this install's device id if it has one. Taking a new id would split
                // this device's causal history across two HLC node identities — and the local
                // database is still full of changes stamped by the old one.
                session = try await HTTPTransport.login(
                    baseURL: serverURL, email: email, password: password, deviceName: deviceName,
                    existingDeviceId: try sessions.knownDeviceId())
            }
            try sessions.save(session)
            // Re-read through the store so the live session carries the rotation hook that
            // writes replaced tokens straight back to the Keychain.
            try attach(session: try sessions.load() ?? session, store: store)
            await sync()
        } catch {
            record(error)
        }
    }

    /// Clears the tokens, keeps the device id and the local database.
    ///
    /// Signing out is not becoming a new device. The outbox may still hold un-pushed edits,
    /// and they are this user's work — they go up on the next sign-in, stamped by the same
    /// node id that made them.
    public func signOut() {
        do {
            _ = try sessions.signOutKeepingDeviceId()
        } catch {
            record(error)
        }
        service = nil
        habits = []
        completions = []
        lastOutcome = nil
        pendingNotices = []
        phase = .signedOut
    }

    // MARK: - Reads

    /// Recomputes every projection from the store.
    ///
    /// Called after each local edit and after each sync. Cheap enough to do wholesale at this
    /// scale, and wholesale is what makes it impossible for the screen to hold a habit the
    /// database no longer has.
    public func refresh() {
        guard let service else { return }
        do {
            habits = try service.habits()
            completions = try service.completions()
            status = try service.status()
        } catch {
            record(error)
        }
    }

    public func weeklyProgress(for habit: Habit) -> (done: Int, target: Int?) {
        let week = Calendar.current.weekInterval()
        return (habit.completionCount(in: completions, week: week), habit.weeklyTarget)
    }

    /// Tombstoned habits, for the undo screen.
    ///
    /// Read on demand rather than kept alongside ``habits``: the main list never wants them,
    /// and a device that has been deleting things for a year should not carry that list around
    /// in memory to render a screen nobody opened.
    public func deletedHabits() -> [Habit] {
        guard let service else { return [] }
        do {
            return try service.habits(includeDeleted: true).filter(\.isDeleted)
        } catch {
            record(error)
            return []
        }
    }

    public func completions(of habit: Habit) -> [Completion] {
        completions.filter { $0.habitId == habit.id }
    }

    // MARK: - Local edits

    /// Every edit below lands on disk before it returns and touches no network. That is the
    /// whole design, not an optimisation: the sync happens later, possibly days later, and the
    /// user is never made to wait on a radio to rename something.

    public func addHabit(name: String, weeklyTarget: Int?, colour: String?) {
        perform { try $0.addHabit(name: name, weeklyTarget: weeklyTarget, colour: colour) }
    }

    public func rename(_ habit: Habit, to name: String) {
        perform { try $0.rename(habit.id, to: name) }
    }

    public func setWeeklyTarget(_ habit: Habit, to target: Int?) {
        perform { try $0.setWeeklyTarget(habit.id, to: target) }
    }

    public func setColour(_ habit: Habit, to colour: String?) {
        perform { try $0.setColour(habit.id, to: colour) }
    }

    public func delete(_ habit: Habit) {
        perform { try $0.delete(habit.id) }
    }

    public func restore(_ habitId: UUID) {
        perform { try $0.restore(habitId) }
    }

    public func logCompletion(of habit: Habit, at date: Date = Date()) {
        perform { try $0.logCompletion(of: habit.id, at: date) }
    }

    public func removeCompletion(_ completion: Completion) {
        perform { try $0.removeCompletion(completion.id) }
    }

    private func perform(_ body: (HabitSyncService) throws -> Void) {
        guard let service else { return }
        do {
            try body(service)
            refresh()
        } catch {
            record(error)
        }
    }

    // MARK: - Sync

    /// Pushes the outbox and pulls what this device missed.
    ///
    /// Failure is expected and mostly uninteresting — an offline-first app spends much of its
    /// life unable to reach anything. What matters is that a *non-retryable* failure gets in
    /// front of the user, because that one will never fix itself.
    public func sync() async {
        guard let service, !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }

        do {
            // The engine caps round trips per call so a device back from a long offline
            // stretch yields instead of draining the whole backlog in one uninterruptible
            // burst. When it says more remains, come straight back rather than waiting for the
            // next schedule — but bounded, so a server that always claims more cannot hold
            // this loop open forever.
            var drains = 0
            while true {
                let outcome = try await service.sync()
                lastOutcome = outcome
                lastSyncedAt = Date()
                // ADR-001 and ADR-003 both accept losing data on the condition that the loss
                // is surfaced. This is where that promise is kept: writes the user made *here*
                // that another device overwrote or hid.
                if !outcome.lostLocalWrites.isEmpty {
                    pendingNotices += outcome.lostLocalWrites
                }
                refresh()

                drains += 1
                guard outcome.moreRemaining, drains < Self.maxDrainsPerSync else { break }
            }
        } catch {
            record(error)
        }
    }

    public func acknowledgeNotices() {
        pendingNotices = []
    }

    public func dismissError() {
        lastError = nil
    }

    private func record(_ error: any Error) {
        let retryable = (error as? TransportError)?.retryable ?? false
        lastError = SyncFailure(
            message: String(describing: error), retryable: retryable, at: Date())
    }
}
