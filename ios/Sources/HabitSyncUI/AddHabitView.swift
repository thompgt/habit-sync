import SwiftUI

#if os(iOS)

/// Creating a habit. Saves locally and dismisses — the network is not involved.
struct AddHabitView: View {

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var hasTarget = false
    @State private var weeklyTarget = 3
    @State private var colour: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Habit") {
                    TextField("Name", text: $name)
                        .textInputAutocapitalization(.sentences)
                }

                Section {
                    Toggle("Weekly target", isOn: $hasTarget)
                    if hasTarget {
                        Stepper("\(weeklyTarget) times a week", value: $weeklyTarget, in: 1...21)
                    }
                } footer: {
                    Text("Progress is counted from the completions you log, so it stays right even when two devices log at once.")
                }

                Section("Colour") {
                    ColourPicker(selection: $colour)
                }
            }
            .navigationTitle("New habit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        model.addHabit(
                            name: name.trimmingCharacters(in: .whitespaces),
                            weeklyTarget: hasTarget ? weeklyTarget : nil,
                            colour: colour)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}

/// A row of colour swatches, with "none" as a real, selectable option.
///
/// "None" matters here: no colour and a cleared colour are different states in the store, and
/// a picker that could only ever set a value would leave the clear path unreachable from the
/// app.
struct ColourPicker: View {
    @Binding var selection: String?

    var body: some View {
        HStack(spacing: 14) {
            swatch(nil)
            ForEach(HabitPalette.names, id: \.self) { name in
                swatch(name)
            }
        }
        .padding(.vertical, 4)
    }

    private func swatch(_ name: String?) -> some View {
        Circle()
            .fill(name == nil ? Color(uiColor: .systemGray5) : HabitPalette.color(named: name))
            .frame(width: 26, height: 26)
            .overlay {
                if selection == name {
                    Circle().strokeBorder(Color.primary, lineWidth: 2)
                }
                if name == nil {
                    Image(systemName: "slash.circle").font(.caption).foregroundStyle(.secondary)
                }
            }
            .onTapGesture { selection = name }
            .accessibilityLabel(name ?? "No colour")
            .accessibilityAddTraits(selection == name ? [.isSelected, .isButton] : .isButton)
    }
}
#endif
