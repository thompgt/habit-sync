import HabitSyncClient
import HabitSyncCore
import SwiftUI

#if os(iOS)

/// What this device knows, what it has not sent yet, and what the last sync did.
///
/// This is the screen that makes the system's behaviour legible instead of magical. The three
/// numbers at the top — watermark, outbox depth, clock — are the same three the reference
/// CLI's `status` prints, and they are the first three questions worth asking when two devices
/// disagree.
@MainActor
struct SyncStatusView: View {

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showingDeleted = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        Task { await model.sync() }
                    } label: {
                        Label(model.isSyncing ? "Syncing…" : "Sync now", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(model.isSyncing)

                    if let at = model.lastSyncedAt {
                        LabeledContent("Last sync", value: at.formatted(date: .omitted, time: .shortened))
                    }
                    if let outcome = model.lastOutcome {
                        LabeledContent("Sent", value: "\(outcome.opsAcknowledged) ops")
                        LabeledContent("Received", value: "\(outcome.changesApplied) changes")
                        if outcome.resynced {
                            Label(
                                "The server asked this device to rebuild from scratch",
                                systemImage: "arrow.clockwise.icloud")
                                .font(.footnote)
                        }
                    }
                } header: {
                    Text("Sync")
                } footer: {
                    if let error = model.lastError, error.retryable {
                        // Retryable failures are the normal condition of a phone, so they live
                        // here rather than in an alert. The distinction is the point: this one
                        // will probably fix itself, and the alert is reserved for the ones
                        // that will not.
                        Text("Last attempt failed and will be retried: \(error.message)")
                    }
                }

                if let status = model.status {
                    Section {
                        LabeledContent("Waiting to send", value: "\(status.pendingOps)")
                        LabeledContent("Watermark", value: "\(status.watermark)")
                        LabeledContent("Entities held", value: "\(status.entityCount)")
                        LabeledContent("Device") {
                            Text(status.deviceId.uuidString.prefix(8))
                                .monospaced()
                        }
                        if let clock = status.clock {
                            LabeledContent("Clock") {
                                Text(clock.compactString).monospaced().lineLimit(1).truncationMode(.middle)
                            }
                        }
                    } header: {
                        Text("This device")
                    } footer: {
                        Text(
                            "The device id is also this device's clock identity, which is what "
                                + "breaks ties when two edits carry the same timestamp. It survives "
                                + "signing out.")
                    }
                }

                if let outcome = model.lastOutcome, !outcome.conflicts.isEmpty {
                    Section {
                        ForEach(outcome.conflicts.prefix(50)) { conflict in
                            ConflictRow(conflict: conflict)
                        }
                        if outcome.unreportedConflicts > 0 {
                            Text("…and \(outcome.unreportedConflicts) more not listed")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } header: {
                        Text("Conflicts in the last sync")
                    } footer: {
                        Text("Last-writer-wins discards work. It is listed here rather than hidden.")
                    }
                }

                Section {
                    Button("Deleted habits") { showingDeleted = true }
                    Button("Sign out", role: .destructive) {
                        model.signOut()
                        dismiss()
                    }
                } footer: {
                    Text("Signing out keeps this device's habits and its identity. Anything not yet sent is sent when you sign back in.")
                }
            }
            .navigationTitle("Sync")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $showingDeleted) { DeletedHabitsView() }
        }
    }
}

/// One conflict, phrased for the person who lost the work rather than for the engine.
@MainActor
struct ConflictRow: View {
    let conflict: Conflict

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(headline)
                .font(.subheadline)
            Text("won by \(conflict.winner.compactString)")
                .font(.caption2)
                .monospaced()
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    private var headline: String {
        switch conflict.kind {
        case .fieldOverwritten:
            let field = conflict.field ?? "a field"
            return conflict.lostLocalWrite
                ? "Your change to \(field) was overwritten by another device"
                : "\(field) was written on two devices at once"
        case .lifecycleContested:
            return conflict.lostLocalWrite
                ? "Your delete or restore was overridden by another device"
                : "A delete and a restore raced"
        case .hiddenByDelete:
            return "Something you edited was deleted on another device"
        }
    }
}

/// The undo path for deletes.
///
/// Restoring is an explicit user action and nothing else — a tombstone is never lifted as a
/// side effect of a later edit arriving, which is what makes deletes stay deleted while
/// leaving them reversible.
@MainActor
struct DeletedHabitsView: View {

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var deleted: [Habit] = []

    var body: some View {
        NavigationStack {
            List {
                if deleted.isEmpty {
                    ContentUnavailableView(
                        "Nothing deleted",
                        systemImage: "trash",
                        description: Text("Deleted habits stay here so you can bring them back."))
                }
                ForEach(deleted) { habit in
                    HStack {
                        Text(habit.name.isEmpty ? "Untitled habit" : habit.name)
                        Spacer()
                        Button("Restore") {
                            model.restore(habit.id)
                            reload()
                        }
                    }
                }
            }
            .navigationTitle("Deleted")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear(perform: reload)
        }
    }

    /// Read on demand rather than kept in the model: tombstoned habits are not something the
    /// main screen ever needs, and loading them lazily keeps the list's refresh cheap.
    private func reload() {
        deleted = model.deletedHabits()
    }
}
#endif
