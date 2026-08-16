import HabitSyncClient
import SwiftUI

// The screens are iOS-only. The package still builds for macOS so `swift test` can run the
// core and client suites from a terminal without booting a simulator, and the modifiers below
// (keyboard types, input autocapitalisation) do not exist there.
#if os(iOS)

/// The app's one entry point: decides between sign-in and the habit list, and owns the model.
@MainActor
public struct RootView: View {

    @State private var model = AppModel()

    public init() {}

    public var body: some View {
        Group {
            switch model.phase {
            case .starting:
                ProgressView("Opening your habits…")
            case .signedOut:
                SignInView()
            case .signedIn:
                HabitListView()
            case .unusable(let detail):
                UnusableStoreView(detail: detail)
            }
        }
        .environment(model)
        .task { await model.start() }
        // The alert is for failures that will not fix themselves. Retryable ones — no
        // network, a 5xx — are the ordinary condition of an offline-first app and are left to
        // the status line rather than interrupting anybody.
        .alert(
            "Sync problem",
            isPresented: Binding(
                get: { model.lastError.map { !$0.retryable } ?? false },
                set: { if !$0 { model.dismissError() } })
        ) {
            Button("OK", role: .cancel) { model.dismissError() }
        } message: {
            Text(model.lastError?.message ?? "")
        }
    }
}

/// Shown when the local database cannot be opened.
///
/// Its own screen rather than an alert over an empty list, because nothing in the app works
/// without it and an empty habit list would read as "you have no habits" — which, for someone
/// whose disk is full, is a lie about their data.
@MainActor
struct UnusableStoreView: View {
    let detail: String

    var body: some View {
        ContentUnavailableView {
            Label("Your habits are unavailable", systemImage: "externaldrive.badge.exclamationmark")
        } description: {
            Text(
                """
                The local database could not be opened. Your data is still on this device; \
                the app cannot read it right now.

                \(detail)
                """)
        }
    }
}
#endif
