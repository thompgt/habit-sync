import HabitSyncClient
import SwiftUI

#if os(iOS)

/// One habit: edit its fields, log and remove completions, delete or restore it.
///
/// Every field is written as its own op the moment editing ends, rather than collected into a
/// "Save" that writes them all. That mirrors what the merge engine does with them — each field
/// is an independent register — so a rename made here and a target change made on another
/// device both survive instead of one silently overwriting the other's untouched fields.
struct HabitDetailView: View {

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    let habitId: UUID

    @State private var name = ""
    @State private var hasTarget = false
    @State private var weeklyTarget = 3
    @State private var colour: String?
    @State private var loaded = false
    @State private var confirmingDelete = false

    private var habit: Habit? {
        model.habits.first { $0.id == habitId }
    }

    var body: some View {
        Form {
            if let habit {
                fields(for: habit)
                completionsSection(for: habit)
                dangerSection(for: habit)
            } else {
                // The habit was deleted — here, or on another device whose delete just arrived.
                ContentUnavailableView(
                    "This habit is gone",
                    systemImage: "trash",
                    description: Text("It was deleted on this device or another one."))
            }
        }
        .navigationTitle(habit?.name ?? "Habit")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: loadOnce)
        // Leaving the screen commits a rename the user typed without pressing return. Without
        // this the edit is simply lost, which on an offline-first app looks exactly like the
        // sync eating it.
        .onDisappear { if let habit { commitName(habit) } }
        .confirmationDialog("Delete this habit?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let habit { model.delete(habit) }
                dismiss()
            }
        } message: {
            Text("Your other devices will hide it too. You can undo this from the deleted list.")
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func fields(for habit: Habit) -> some View {
        Section("Habit") {
            TextField("Name", text: $name)
                .textInputAutocapitalization(.sentences)
                // Written when the field loses focus rather than on every keystroke: one op
                // per rename, not one per letter, each of which would occupy a sequence
                // number and a row in the server's replication log.
                .onSubmit { commitName(habit) }
        }

        Section {
            Toggle("Weekly target", isOn: $hasTarget)
                .onChange(of: hasTarget) { _, isOn in
                    model.setWeeklyTarget(habit, to: isOn ? weeklyTarget : nil)
                }
            if hasTarget {
                Stepper("\(weeklyTarget) times a week", value: $weeklyTarget, in: 1...21)
                    .onChange(of: weeklyTarget) { _, value in
                        model.setWeeklyTarget(habit, to: value)
                    }
            }
        } footer: {
            Text("Turning the target off clears the field, which is different from never having set one — and that difference travels to your other devices.")
        }

        Section("Colour") {
            ColourPicker(selection: $colour)
                .onChange(of: colour) { _, value in
                    model.setColour(habit, to: value)
                }
        }
    }

    @ViewBuilder
    private func completionsSection(for habit: Habit) -> some View {
        let logged = model.completions(of: habit)
        let progress = model.weeklyProgress(for: habit)

        Section {
            Button {
                model.logCompletion(of: habit)
            } label: {
                Label("Log a completion", systemImage: "checkmark.circle")
            }

            ForEach(logged.prefix(20)) { completion in
                Text(completion.at.formatted(date: .abbreviated, time: .shortened))
                    .swipeActions {
                        Button("Remove", role: .destructive) {
                            model.removeCompletion(completion)
                        }
                    }
            }
        } header: {
            Text("Completions")
        } footer: {
            if let target = progress.target {
                Text("\(progress.done) of \(target) this week. \(logged.count) logged in total.")
            } else {
                Text("\(progress.done) this week. \(logged.count) logged in total.")
            }
        }
    }

    @ViewBuilder
    private func dangerSection(for habit: Habit) -> some View {
        Section {
            Button("Delete habit", role: .destructive) { confirmingDelete = true }
        } footer: {
            Text("Deleting keeps the record on your devices as a tombstone, which is how the deletion reaches a device that is currently offline.")
        }
    }

    // MARK: - Editing

    /// Seeds the editable fields once, from the merged record.
    ///
    /// Once, deliberately. Re-seeding whenever the model changes would let a sync landing
    /// mid-edit overwrite what the user is typing — the field they are in is the one place
    /// last-writer-wins should not apply, because the last writer has not finished writing.
    private func loadOnce() {
        guard !loaded, let habit else { return }
        name = habit.name
        hasTarget = habit.weeklyTarget != nil
        weeklyTarget = habit.weeklyTarget ?? 3
        colour = habit.colour
        loaded = true
    }

    private func commitName(_ habit: Habit) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        // No-op writes are worth suppressing: each one costs an op, a round trip and a row in
        // the server's log, and produces exactly the state that was already there.
        guard !trimmed.isEmpty, trimmed != habit.name else { return }
        model.rename(habit, to: trimmed)
    }
}
#endif
