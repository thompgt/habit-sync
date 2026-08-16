import Foundation
import HabitSyncCore

/// A sync that runs with no user interface attached — the background refresh path.
///
/// It builds its own store, session and engine rather than sharing the app's, and that is the
/// safe way round: the two never run at the same moment in practice (the system does not wake
/// an app that is already frontmost), and if they ever did, both go through SQLite
/// transactions and an outbox that is idempotent by op id. What it must *not* do is hold a
/// reference to the UI's model and mutate it from a task the app cannot see.
///
/// Everything it needs is already durable: the session is in the Keychain, the outbox and the
/// watermark are in the database. A background wake therefore needs no state handed to it,
/// which is what makes it able to run at all — the system may launch the app cold for this.
public enum HeadlessSync {

    /// Runs one sync, or returns `nil` if this device is not signed in.
    ///
    /// - Returns: the outcome, so the caller can tell the system whether the wake was
    ///   productive. Reporting a wake that did nothing as successful is how an app's refresh
    ///   budget gets spent on nothing.
    @discardableResult
    public static func run(serverURL: String) async throws -> SyncOutcome? {
        let sessions = KeychainSessionStore()
        guard let session = try sessions.load(), !session.refreshToken.isEmpty else { return nil }

        let store = try SQLiteLocalStore.defaultLocation()
        let transport = HTTPTransport(baseURL: serverURL, session: session)
        let service = try HabitSyncService(session: session, store: store, transport: transport)
        return try await service.sync()
    }
}
