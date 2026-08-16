import Foundation
import Security

/// Where the device's session lives between launches: the Keychain.
///
/// Not `UserDefaults`, and not a file next to the database. The refresh token is a
/// long-lived bearer credential for the whole account, and the two properties that matter
/// here are that it survives an app upgrade and that it is not readable from a plist by
/// anything that can see the container.
///
/// The accessibility class is deliberate. `AfterFirstUnlock` — not `WhenUnlocked` — because
/// the background sync task runs on the system's schedule, which is frequently while the
/// phone is in a pocket and locked. `WhenUnlocked` would make every background sync fail to
/// read its own credentials, and the symptom would be an app that syncs only when watched.
/// `ThisDeviceOnly` because the device id doubles as the HLC node id: a session restored
/// onto a *second* physical device from an iCloud Keychain backup would give two devices one
/// node identity, which is the one client-side way to break convergence outright.
public struct KeychainSessionStore: Sendable {

    /// One record per server, so pointing a debug build at localhost does not evict the
    /// session for the production host.
    private let service: String
    private let account: String

    public init(service: String = "dev.thompgt.habitsync.session", account: String = "default") {
        self.service = service
        self.account = account
    }

    /// The persisted shape. Kept separate from ``Session`` because `Session` is mutable and
    /// carries a rotation callback, neither of which belongs in storage.
    struct Stored: Codable, Sendable {
        var userId: UUID
        var deviceId: UUID
        var email: String
        var accessToken: String
        var refreshToken: String
    }

    public enum StoreError: Error, CustomStringConvertible {
        case keychain(OSStatus)
        case corrupt(String)

        public var description: String {
            switch self {
            case .keychain(let status): return "Keychain operation failed with status \(status)"
            case .corrupt(let detail): return "Stored session is unreadable: \(detail)"
            }
        }
    }

    // MARK: - API

    /// Reads the saved session, or `nil` if this device has never signed in.
    public func load() throws -> Session? {
        guard let stored = try loadStored() else { return nil }
        return session(from: stored)
    }

    /// Saves `session`, replacing whatever was there.
    public func save(_ session: Session) throws {
        let stored = Stored(
            userId: session.userId,
            deviceId: session.deviceId,
            email: session.email,
            accessToken: session.accessToken,
            refreshToken: session.refreshToken)
        try write(try JSONEncoder().encode(stored))
    }

    /// Forgets the tokens but **keeps the device id**, so a later sign-in can present it.
    ///
    /// Signing out is not the same as becoming a new device. Taking a fresh device id on the
    /// next sign-in would split this device's causal history across two HLC node identities,
    /// and the local database — which is not cleared here either — would still be full of
    /// changes stamped by the old one.
    public func signOutKeepingDeviceId() throws -> UUID? {
        guard let stored = try loadStored() else { return nil }
        try delete()
        try write(try JSONEncoder().encode(
            Stored(
                userId: stored.userId, deviceId: stored.deviceId, email: stored.email,
                accessToken: "", refreshToken: "")))
        return stored.deviceId
    }

    /// The device id this install has used before, if any — passed to `login` so the identity
    /// survives a sign-out and sign-in.
    public func knownDeviceId() throws -> UUID? {
        try loadStored()?.deviceId
    }

    /// Removes the record entirely. Only correct when the local database is being discarded
    /// too; otherwise prefer ``signOutKeepingDeviceId()``.
    public func erase() throws {
        try delete()
    }

    /// Builds a live ``Session`` wired to write itself back on token rotation.
    private func session(from stored: Stored) -> Session {
        let session = Session(
            userId: stored.userId,
            deviceId: stored.deviceId,
            email: stored.email,
            accessToken: stored.accessToken,
            refreshToken: stored.refreshToken)
        // Rotation happens inside the transport, mid-sync, and the new refresh token is only
        // usable once. If it is not persisted the moment it arrives, a crash or a kill before
        // the next save presents an already-exchanged token on the next launch — which this
        // server reads as theft and answers by revoking every session on the account.
        session.onRotation { [self] rotated in
            try? save(rotated)
        }
        return session
    }

    // MARK: - Keychain plumbing

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func loadStored() throws -> Stored? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else {
            throw StoreError.keychain(status)
        }
        do {
            return try JSONDecoder().decode(Stored.self, from: data)
        } catch {
            // A record written by an older, incompatible version. Deleting it beats leaving
            // the app permanently unable to sign in, and the only thing lost is a token pair
            // that a fresh sign-in replaces anyway.
            try? delete()
            throw StoreError.corrupt(String(describing: error))
        }
    }

    private func write(_ data: Data) throws {
        try delete()
        var attributes = baseQuery()
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw StoreError.keychain(status) }
    }

    private func delete() throws {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw StoreError.keychain(status)
        }
    }
}
