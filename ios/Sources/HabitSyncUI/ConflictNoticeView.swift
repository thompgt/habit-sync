import HabitSyncCore
import SwiftUI

#if os(iOS)

/// Shown when a sync discarded or hid work the user did **on this device**.
///
/// The whole reason this screen exists is stated in ADR-001 and ADR-003: last-writer-wins
/// loses data, and a tombstone hides concurrent edits, and both are acceptable *only* because
/// the loss is shown rather than swallowed. Silent data loss is a bug; visible loss under a
/// documented rule is a trade-off someone can reason about.
///
/// It is deliberately limited to losses this device authored. A write another replica lost to
/// a value this device already held is real, but interrupting someone over it would train them
/// to dismiss the notice that matters. Those live in the sync screen instead.
struct ConflictNoticeView: View {

    let conflicts: [Conflict]
    let acknowledge: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(conflicts) { conflict in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(headline(conflict))
                                .font(.subheadline)
                            Text(detail(conflict))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                } footer: {
                    Text(
                        "When two devices change the same thing while apart, the later change "
                            + "wins. The other one is not recoverable, which is why you are being "
                            + "told about it.")
                }
            }
            .navigationTitle(conflicts.count == 1 ? "A change was overwritten" : "Changes were overwritten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") {
                        acknowledge()
                        dismiss()
                    }
                }
            }
        }
        // Not dismissible by swiping away: this is the one notice the app owes the user, and
        // an accidental swipe would drop it with no way back — the report is not stored
        // anywhere the user can go and find it later.
        .interactiveDismissDisabled()
    }

    private func headline(_ conflict: Conflict) -> String {
        switch conflict.kind {
        case .fieldOverwritten:
            return "Your change to \(conflict.field ?? "a field") was replaced"
        case .lifecycleContested:
            return "Your delete or restore was overridden"
        case .hiddenByDelete:
            return "A habit you edited was deleted elsewhere"
        }
    }

    private func detail(_ conflict: Conflict) -> String {
        let when = Date(timeIntervalSince1970: Double(conflict.winner.physicalMillis) / 1000)
        switch conflict.kind {
        case .hiddenByDelete:
            return "Another device deleted it at \(when.formatted(date: .abbreviated, time: .shortened)). Your edit is still stored and comes back if the habit is restored."
        default:
            return "Another device made a later change at \(when.formatted(date: .abbreviated, time: .shortened))."
        }
    }
}
#endif
