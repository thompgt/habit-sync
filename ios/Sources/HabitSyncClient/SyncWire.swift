import Foundation
import HabitSyncCore

/// The JSON bodies of `/v1/sync` and `/v1/auth`, as this client sends and expects them.
///
/// A separate declaration from the server's `SyncDtos` rather than a shared one, and that is
/// a real trade-off rather than an oversight — the two are in different languages here, so
/// sharing was never on the table, but the same discipline applies: what is shared is the
/// part where drift actually costs data. ``WireChange`` and ``ChangeCodec`` are ported
/// field-for-field from `sync-core`, and both ends encode changes through them. The envelope
/// around them is small enough to be checked by an end-to-end test against a running server.
enum SyncWire {

    /// Must match the server's `SyncDtos.PROTOCOL_VERSION`, or it answers 426.
    static let protocolVersion = 1

    struct Request: Encodable {
        let sinceSeq: Int64
        let protocolVersion: Int
        let ops: [WireChange]
    }

    struct Response: Decodable {
        // Optional on the way in because a server is free to omit an empty list, and a
        // decoding failure here would look to the engine like an unsyncable device.
        let appliedOpIds: [UUID]?
        let changes: [ChangeEnvelope]?
        let nextSeq: Int64
        let hasMore: Bool
        let resyncRequired: Bool
        let resyncReason: String?
        let serverTimeMillis: Int64
        let protocolVersion: Int
    }

    struct ChangeEnvelope: Decodable {
        let serverSeq: Int64
        let change: WireChange
    }

    struct LoginRequest: Encodable {
        let email: String
        let password: String
        let deviceName: String
        /// The caller's existing device id, if it has one. Supplying it keeps the device's
        /// HLC node identity stable across sign-ins; omitting it registers a new device.
        let deviceId: UUID?
    }

    struct RegisterRequest: Encodable {
        let email: String
        let password: String
        let deviceName: String
    }

    struct RefreshRequest: Encodable {
        let refreshToken: String
    }

    struct TokenResponse: Decodable {
        let accessToken: String
        let refreshToken: String
        let tokenType: String?
        let expiresIn: Int64
        let userId: UUID
        let deviceId: UUID
    }
}
