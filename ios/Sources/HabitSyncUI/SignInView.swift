import SwiftUI

#if os(iOS)
import UIKit

/// Sign-in and registration, plus the server address.
///
/// The server field is visible rather than hidden behind a debug build. This app is one half
/// of a system whose whole point is watching two devices converge, and pointing a phone at a
/// laptop running the server is the demonstration — hiding that behind a rebuild would make
/// the interesting case the hard one.
@MainActor
struct SignInView: View {

    @Environment(AppModel.self) private var model

    @State private var email = ""
    @State private var password = ""
    @State private var deviceName = SignInView.suggestedDeviceName()
    @State private var isRegistering = false
    @State private var working = false

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            Form {
                Section("Account") {
                    TextField("Email", text: $email)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Password", text: $password)
                        .textContentType(isRegistering ? .newPassword : .password)
                    if isRegistering {
                        // Stated up front rather than as a server error after the round trip.
                        // The rule is length-only by design: length dominates character-class
                        // requirements for real strength, and composition rules mostly push
                        // people towards Password1!.
                        Text("At least 12 characters. No other rules.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    TextField("Device name", text: $deviceName)
                        .textInputAutocapitalization(.words)
                } header: {
                    Text("This device")
                } footer: {
                    Text(
                        "Shown on your other devices. This device keeps its identity across "
                            + "sign-outs, which is what keeps its edits ordered correctly.")
                }

                Section {
                    TextField("Server", text: $model.serverURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text("Server")
                } footer: {
                    Text(
                        "The simulator reaches a server on your Mac at http://localhost:8080. "
                            + "A real device needs your Mac's address on the network.")
                }

                Section {
                    Button(isRegistering ? "Create account" : "Sign in") {
                        Task { await submit() }
                    }
                    .disabled(!canSubmit)

                    Button(isRegistering ? "I already have an account" : "Create an account instead") {
                        isRegistering.toggle()
                    }
                    .foregroundStyle(.secondary)
                }

                if let error = model.lastError {
                    Section {
                        Label(error.message, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
                }
            }
            .navigationTitle(isRegistering ? "Create account" : "Sign in")
            .disabled(working)
            .overlay {
                if working { ProgressView().controlSize(.large) }
            }
        }
    }

    private var canSubmit: Bool {
        !email.trimmingCharacters(in: .whitespaces).isEmpty
            && password.count >= (isRegistering ? 12 : 1)
            && !working
    }

    private func submit() async {
        working = true
        defer { working = false }
        await model.signIn(
            email: email.trimmingCharacters(in: .whitespaces),
            password: password,
            deviceName: deviceName.isEmpty ? "iPhone" : deviceName,
            register: isRegistering)
    }

    /// The device's own name, which is what the user will recognise in a device list.
    private static func suggestedDeviceName() -> String {
        UIDevice.current.name
    }
}
#endif
