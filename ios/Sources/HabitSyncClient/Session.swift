import Foundation

/// The device's credentials: a short-lived access token, the long-lived refresh token that
/// renews it, and the identity both belong to.
///
/// Mutable, because refresh rotation replaces both tokens and the replacement must be
/// visible to the next request. Rotation is not optional on this server: presenting a
/// refresh token that has already been exchanged is treated as evidence of theft and revokes
/// every session on the account. A client that kept the old token after refreshing would log
/// itself out, and would look like an attack while doing it.
///
/// ``deviceId`` is the load-bearing field. It doubles as the HLC node id, so it must survive
/// restarts and reinstalls-with-restore — a device that forgets it and registers afresh
/// fragments its own causal history across two identities and loses the `nodeId` tiebreak
/// that makes the clock ordering total.
public final class Session: @unchecked Sendable {

    public let userId: UUID
    public let deviceId: UUID
    public let email: String

    private let lock = NSLock()
    private var access: String
    private var refresh: String

    /// Called whenever rotation replaces the tokens, so they can be written back to storage.
    ///
    /// A closure rather than a delegate because there is exactly one thing that ever wants
    /// this, and because the transport must not be able to forget to call it: rotation that
    /// is not persisted logs the user out of every device on the next attempt.
    private var onRotate: (@Sendable (Session) -> Void)?

    public init(
        userId: UUID, deviceId: UUID, email: String, accessToken: String, refreshToken: String
    ) {
        self.userId = userId
        self.deviceId = deviceId
        self.email = email
        self.access = accessToken
        self.refresh = refreshToken
    }

    /// The HLC node id for this device. UUID text contains no ':', which ``Hlc`` forbids.
    public var nodeId: String { deviceId.uuidString.lowercased() }

    public var accessToken: String {
        lock.lock()
        defer { lock.unlock() }
        return access
    }

    public var refreshToken: String {
        lock.lock()
        defer { lock.unlock() }
        return refresh
    }

    public func onRotation(_ handler: @escaping @Sendable (Session) -> Void) {
        lock.lock()
        onRotate = handler
        lock.unlock()
    }

    /// Stores both halves of a rotated pair and notifies the persistence hook.
    ///
    /// Both, always. The server rotates on every refresh and treats a re-presented token as
    /// theft, revoking the whole account's sessions — so dropping the new refresh token on
    /// the floor here would log the user out of every device on the next attempt.
    func replaceTokens(access newAccess: String, refresh newRefresh: String) {
        lock.lock()
        access = newAccess
        refresh = newRefresh
        let handler = onRotate
        lock.unlock()
        handler?(self)
    }
}
