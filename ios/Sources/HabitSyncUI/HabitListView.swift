import HabitSyncClient
import SwiftUI

#if os(iOS)

/// The main screen: this week's habits, their progress, and one tap to log a completion.
@MainActor
struct HabitListView: View {

    @Environment(AppModel.self) private var model
    @State private var isAdding = false
    @State private var showingStatus = false

    var body: some View {
        NavigationStack {
            List {
                if model.habits.isEmpty {
                    ContentUnavailableView {
                        Label("No habits yet", systemImage: "checklist")
                    } description: {
                        Text("Add one. It is saved on this device straight away — no network needed.")
                    }
                    .listRowSeparator(.hidden)
                } else {
                    Section {
                        ForEach(model.habits) { habit in
                            HabitRow(habit: habit)
                        }
                        .onDelete(perform: delete)
                    } footer: {
                        Text(footerText)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationDestination(for: UUID.self) { id in
                HabitDetailView(habitId: id)
            }
            .navigationTitle("Habits")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showingStatus = true
                    } label: {
                        Label("Sync status", systemImage: syncIcon)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isAdding = true
                    } label: {
                        Label("Add habit", systemImage: "plus")
                    }
                }
            }
            // Pull to refresh runs a real sync. It is the only place in the app that waits on
            // the network, and it waits because the user asked it to.
            .refreshable { await model.sync() }
            .sheet(isPresented: $isAdding) { AddHabitView() }
            .sheet(isPresented: $showingStatus) { SyncStatusView() }
            // Presented off the model's own state, and dismissing acknowledges the notices.
            // A `.constant` binding here would put up a sheet the user cannot close.
            .sheet(
                isPresented: Binding(
                    get: { !model.pendingNotices.isEmpty },
                    set: { if !$0 { model.acknowledgeNotices() } })
            ) {
                ConflictNoticeView(conflicts: model.pendingNotices) { model.acknowledgeNotices() }
            }
        }
    }

    /// The outbox depth, in words. A number that stays above zero after a sync is the single
    /// most useful signal that something is wrong, so it is on the main screen rather than
    /// buried in a debug panel.
    private var footerText: String {
        let pending = model.status?.pendingOps ?? 0
        if pending == 0 {
            guard let at = model.lastSyncedAt else { return "Not synced on this device yet." }
            return "Everything on this device has reached the server. Last sync \(at.formatted(.relative(presentation: .named)))."
        }
        return pending == 1
            ? "1 change waiting to sync. It is saved here and safe."
            : "\(pending) changes waiting to sync. They are saved here and safe."
    }

    private var syncIcon: String {
        if model.isSyncing { return "arrow.triangle.2.circlepath" }
        return (model.status?.pendingOps ?? 0) > 0 ? "icloud.and.arrow.up" : "checkmark.icloud"
    }

    /// Swipe-to-delete tombstones the habit; it does not remove the row's history.
    ///
    /// The completions it accumulated stay in the store as their own entities, which is what
    /// lets a restore bring the habit back with its record intact.
    private func delete(at offsets: IndexSet) {
        for index in offsets {
            model.delete(model.habits[index])
        }
    }
}

/// One habit: its colour, name, this week's progress, and the log button.
@MainActor
struct HabitRow: View {

    @Environment(AppModel.self) private var model
    let habit: Habit

    var body: some View {
        let progress = model.weeklyProgress(for: habit)

        NavigationLink(value: habit.id) {
            HStack(spacing: 12) {
                Circle()
                    .fill(HabitPalette.color(named: habit.colour))
                    .frame(width: 12, height: 12)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(habit.name.isEmpty ? "Untitled habit" : habit.name)
                        .font(.body)
                    Text(progressText(progress))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button {
                    model.logCompletion(of: habit)
                } label: {
                    Image(systemName: "checkmark.circle")
                        .font(.title2)
                }
                // .borderless so the button does not swallow the whole row's tap on iOS, which
                // would make the habit impossible to open.
                .buttonStyle(.borderless)
                .accessibilityLabel("Log a completion of \(habit.name)")
            }
        }
    }

    private func progressText(_ progress: (done: Int, target: Int?)) -> String {
        guard let target = progress.target, target > 0 else {
            return progress.done == 1 ? "1 this week" : "\(progress.done) this week"
        }
        return "\(progress.done) of \(target) this week"
    }
}

/// The habit colours, as the strings actually stored in the `colour` register.
///
/// Stored as names rather than hex, because the same account is edited from a terminal client
/// where `--colour red` is what a person types. Two devices must write byte-identical values
/// for the same logical colour or convergence checks report a difference that is not one.
enum HabitPalette {
    static let names = ["red", "orange", "yellow", "green", "teal", "blue", "indigo", "purple", "pink"]

    static func color(named name: String?) -> Color {
        switch name?.lowercased() {
        case "red": return .red
        case "orange": return .orange
        case "yellow": return .yellow
        case "green": return .green
        case "teal": return .teal
        case "blue": return .blue
        case "indigo": return .indigo
        case "purple": return .purple
        case "pink": return .pink
        // Includes the unset case and any colour a newer client or the CLI invented. Showing
        // grey is better than refusing to draw a habit over a value this build does not know.
        default: return .gray
        }
    }
}
#endif
