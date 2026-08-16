import BackgroundTasks
import HabitSyncClient
import HabitSyncUI
import SwiftUI

/// The app shell: one window, plus the background refresh registration.
///
/// Everything else lives in the package targets. Keeping the shell this thin is what lets the
/// screens be previewed and the engine be tested without an app target at all.
@main
struct HabitSyncApp: App {

    /// Must match the identifier in Info.plist's `BGTaskSchedulerPermittedIdentifiers`, or the
    /// registration below throws at launch.
    static let refreshTaskIdentifier = "dev.thompgt.habitsync.refresh"

    init() {
        Self.registerBackgroundRefresh()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .onChange(of: scenePhase) { _, phase in
                    // Scheduled as the app leaves the foreground, not at launch: the system
                    // grants these on a budget shaped by how the person actually uses the app,
                    // and asking on the way out is the point at which there is something worth
                    // sending.
                    if phase == .background { Self.scheduleBackgroundRefresh() }
                }
        }
    }

    @Environment(\.scenePhase) private var scenePhase

    /// Registers the handler for background wakes.
    ///
    /// The work it does is a plain sync. There is nothing special about a background sync in
    /// this design — the outbox is durable, the watermark is durable, and a wake that is killed
    /// halfway costs one re-pushed page and no data, because every op carries an id the server
    /// deduplicates on.
    private static func registerBackgroundRefresh() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.refreshTaskIdentifier, using: nil
        ) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            Self.handle(task)
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        // Ask for the next wake first. If this one is killed for running long, the chain has
        // already been continued; scheduling at the end would end it.
        scheduleBackgroundRefresh()

        let serverURL = UserDefaults.standard.string(forKey: "dev.thompgt.habitsync.serverURL")
            ?? "http://localhost:8080"

        let work = Task {
            do {
                let outcome = try await HeadlessSync.run(serverURL: serverURL)
                // "Did this wake accomplish anything" is what the system uses to decide how
                // generous to be next time. A sync that reached the server and found nothing
                // still counts as success; one that could not reach it does not.
                task.setTaskCompleted(success: outcome != nil)
            } catch {
                task.setTaskCompleted(success: false)
            }
        }

        // Expiry means the system is taking the time back. Cancelling leaves the outbox and
        // the watermark exactly where they were — no half-applied page, because the store
        // advances both in one transaction or neither.
        task.expirationHandler = { work.cancel() }
    }

    static func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTaskIdentifier)
        // A floor, not a promise: the system decides when. Fifteen minutes matches how stale a
        // habit tracker can afford to be — the user's own edits are already on their device,
        // and this only affects how quickly another device's edits arrive.
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
