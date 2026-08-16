import Foundation
import HabitSyncCore

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The real transport: HTTP to `/v1/sync`, bearer tokens, and refresh-on-401.
///
/// ``SyncEngine`` knows none of this. Everything below is what ``Transport``'s one method
/// exists to hide, which is why the same engine runs unchanged against this and against an
/// in-process test double.
///
/// ## Retryable versus not, and why it matters here
///
/// The engine treats a ``TransportError`` as "your outbox and watermark are untouched, try
/// later". That is safe for a timeout and wrong for a 400: a malformed op retried forever is
/// a device that never syncs again and never says why. So failures are classified rather
/// than blanket-thrown — anything the server could plausibly answer differently next time is
/// retryable, and anything describing a defect in the request is not.
///
/// A lost response is deliberately indistinguishable from a lost request here, because it is
/// indistinguishable in reality. Both surface as a retryable failure, the engine re-pushes,
/// and the server's op-id idempotency makes the replay a no-op. This class must simply not
/// get in the way of that by, for example, clearing anything optimistically.
///
/// ## Refresh
///
/// A 401 triggers exactly one refresh-and-retry. Looping would turn a revoked session into an
/// infinite request storm against the auth endpoint, and the server revokes whole sessions on
/// refresh-token reuse — so a client that retried refresh in a loop would be
/// indistinguishable from the token theft that rule exists to detect.
/// `@unchecked` because `URLSession` and the JSON coders predate `Sendable` annotation on
/// every platform this builds for. Every stored property here is immutable, and the one piece
/// of mutable state in the flow — the token pair — lives behind ``Session``'s lock.
public final class HTTPTransport: Transport, @unchecked Sendable {

    private static let requestTimeout: TimeInterval = 30

    /// Kept as text rather than a `URL`, deliberately. The server address is typed by the user
    /// on the sign-in screen, and `URL(string:)!` on that input is a crash on a typo — whereas
    /// building the URL per request turns the same typo into a non-retryable transport failure
    /// the app can show.
    private let baseURL: String
    private let session: Session
    private let urlSession: URLSession

    public init(baseURL: String, session: Session, urlSession: URLSession? = nil) {
        self.baseURL = HTTPTransport.trimSlash(baseURL)
        self.session = session
        self.urlSession = urlSession ?? HTTPTransport.defaultURLSession()
    }

    /// A session configured for this protocol.
    ///
    /// `waitsForConnectivity` is off deliberately. On an offline-first client the correct
    /// response to no network is to fail fast and leave the outbox alone — the edits are
    /// already durable on disk, and a request parked for ten minutes waiting for a radio
    /// holds the sync loop open for no gain.
    ///
    /// The cache is disabled because every response here is a one-shot delta keyed by a
    /// watermark; caching one could only ever serve a stale page.
    static func defaultURLSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = requestTimeout
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }

    // MARK: - Protocol

    public func exchange(_ request: SyncRequest) async throws -> SyncResponse {
        let ops = request.ops.map(ChangeCodec.encode)
        let body = SyncWire.Request(
            sinceSeq: request.sinceSeq, protocolVersion: SyncWire.protocolVersion, ops: ops)

        let (data, status) = try await send(path: "/v1/sync", body: body, authenticated: true)

        if status == 426 {
            throw TransportError(
                "Server rejected protocol version \(SyncWire.protocolVersion); this client must upgrade",
                retryable: false)
        }
        guard status == 200 else { throw HTTPTransport.failure(status: status, data: data, path: "/v1/sync") }

        let parsed: SyncWire.Response = try decode(data)
        guard parsed.protocolVersion == SyncWire.protocolVersion else {
            throw TransportError(
                "Server answered protocol version \(parsed.protocolVersion), expected \(SyncWire.protocolVersion)",
                retryable: false)
        }
        return try toCore(parsed)
    }

    private func toCore(_ wire: SyncWire.Response) throws -> SyncResponse {
        var changes: [SequencedChange] = []
        for envelope in wire.changes ?? [] {
            do {
                // Decoded through the same codec the server encodes with, so this client
                // accepts exactly what the server accepts. A change the server would have
                // rejected is one this rejects too, rather than one it silently mis-applies.
                changes.append(
                    SequencedChange(serverSeq: envelope.serverSeq, change: try ChangeCodec.decode(envelope.change)))
            } catch {
                // Not retryable: the same bytes will fail identically forever, and the
                // watermark must not advance past a change that was never applied.
                throw TransportError(
                    "Server sent a change this client cannot decode", retryable: false, underlying: error)
            }
        }

        return try SyncResponse(
            appliedOpIds: Set(wire.appliedOpIds ?? []),
            changes: changes,
            nextSeq: wire.nextSeq,
            hasMore: wire.hasMore,
            resyncRequired: wire.resyncRequired,
            resyncReason: wire.resyncReason,
            serverTimeMillis: wire.serverTimeMillis)
    }

    // MARK: - Auth

    /// Registers a new account and returns its session.
    public static func register(
        baseURL: String, email: String, password: String, deviceName: String,
        urlSession: URLSession? = nil
    ) async throws -> Session {
        let token: SyncWire.TokenResponse = try await authCall(
            baseURL: baseURL, path: "/v1/auth/register",
            body: SyncWire.RegisterRequest(email: email, password: password, deviceName: deviceName),
            urlSession: urlSession)
        return makeSession(from: token, email: email)
    }

    /// Signs in, keeping `existingDeviceId` if the caller has one.
    ///
    /// Passing it is not an optimisation. The device id is the HLC node id; taking a new one
    /// on every sign-in splits one device's causal history across several identities, and the
    /// nodeId tiebreak that makes HLC ordering total stops distinguishing what it was there
    /// to distinguish.
    public static func login(
        baseURL: String, email: String, password: String, deviceName: String,
        existingDeviceId: UUID?, urlSession: URLSession? = nil
    ) async throws -> Session {
        let token: SyncWire.TokenResponse = try await authCall(
            baseURL: baseURL, path: "/v1/auth/login",
            body: SyncWire.LoginRequest(
                email: email, password: password, deviceName: deviceName, deviceId: existingDeviceId),
            urlSession: urlSession)
        return makeSession(from: token, email: email)
    }

    private static func makeSession(from token: SyncWire.TokenResponse, email: String) -> Session {
        Session(
            userId: token.userId, deviceId: token.deviceId, email: email,
            accessToken: token.accessToken, refreshToken: token.refreshToken)
    }

    private static func authCall<Body: Encodable>(
        baseURL: String, path: String, body: Body, urlSession: URLSession?
    ) async throws -> SyncWire.TokenResponse {
        let client = urlSession ?? defaultURLSession()
        guard let url = URL(string: trimSlash(baseURL) + path) else {
            throw TransportError("\(baseURL) is not a usable server address", retryable: false)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await perform(request, on: client, path: path)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            // 5xx may resolve on its own; a 401 or 400 here means the credentials or the
            // request are wrong, and retrying identical bytes will never fix that.
            throw TransportError(
                "\(path) failed: HTTP \(status) \(bodyText(data))",
                retryable: status / 100 == 5)
        }
        do {
            return try JSONDecoder().decode(SyncWire.TokenResponse.self, from: data)
        } catch {
            throw TransportError("Malformed response body", retryable: false, underlying: error)
        }
    }

    /// Exchanges the refresh token for a new pair, storing both.
    ///
    /// Both, always. The server rotates on every refresh and treats a re-presented token as
    /// theft, revoking the whole account's sessions — so dropping the new refresh token on
    /// the floor here would log the user out of every device on the next attempt. ``Session``
    /// persists them as they arrive, before the retried request can fail.
    private func refresh() async -> Bool {
        do {
            let (data, status) = try await send(
                path: "/v1/auth/refresh",
                body: SyncWire.RefreshRequest(refreshToken: session.refreshToken),
                authenticated: false)
            guard (200..<300).contains(status) else { return false }
            let token: SyncWire.TokenResponse = try decode(data)
            session.replaceTokens(access: token.accessToken, refresh: token.refreshToken)
            return true
        } catch {
            return false
        }
    }

    // MARK: - Plumbing

    private func send<Body: Encodable>(
        path: String, body: Body, authenticated: Bool
    ) async throws -> (Data, Int) {
        let first = try await sendOnce(path: path, body: body, authenticated: authenticated)
        guard authenticated, first.1 == 401, await refresh() else { return first }
        // Exactly one retry. See the type comment: looping against a revoked session would be
        // indistinguishable from the token theft the server watches for.
        return try await sendOnce(path: path, body: body, authenticated: true)
    }

    private func sendOnce<Body: Encodable>(
        path: String, body: Body, authenticated: Bool
    ) async throws -> (Data, Int) {
        guard let url = URL(string: baseURL + path) else {
            throw TransportError("\(baseURL)\(path) is not a usable server address", retryable: false)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = Self.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if authenticated {
            request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        }
        do {
            // A fresh coder per call rather than a shared one: JSONEncoder is a class with
            // no documented thread-safety guarantee, and the sync task and a foreground
            // refresh can both be in here at once. Allocating one is a few hundred
            // nanoseconds against a network round trip.
            request.httpBody = try JSONEncoder().encode(body)
        } catch {
            throw TransportError("Could not encode the request body", retryable: false, underlying: error)
        }

        let (data, response) = try await Self.perform(request, on: urlSession, path: path)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private static func perform(
        _ request: URLRequest, on client: URLSession, path: String
    ) async throws -> (Data, URLResponse) {
        do {
            return try await client.data(for: request)
        } catch let error as URLError where error.code == .cancelled {
            // The task was cancelled — the app went to the background mid-sync, or the user
            // left the screen. Not a server problem and not worth surfacing as a failure the
            // user must act on, but still retryable: the work is untouched on disk.
            throw TransportError("\(path) was cancelled", retryable: true, underlying: error)
        } catch {
            // A timeout or a dropped connection. The server may or may not have committed —
            // that ambiguity is the whole reason push is idempotent — so this is retryable.
            throw TransportError("\(path) failed", retryable: true, underlying: error)
        }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw TransportError("Malformed response body", retryable: false, underlying: error)
        }
    }

    /// Classifies a non-200 into retryable or not.
    ///
    /// 5xx and 429 are the server's problem and may resolve; 4xx describes this request and
    /// will not. Retrying a 400 forever is how a device with one poison op stops syncing
    /// permanently while reporting nothing but "sync failed".
    private static func failure(status: Int, data: Data, path: String) -> TransportError {
        TransportError(
            "\(path) failed: HTTP \(status) \(bodyText(data))",
            retryable: status / 100 == 5 || status == 429)
    }

    /// Error bodies are small by design; truncating keeps a stray HTML page out of the log.
    private static func bodyText(_ data: Data) -> String {
        let text = String(data: data, encoding: .utf8) ?? "<\(data.count) bytes>"
        return text.count <= 500 ? text : String(text.prefix(500)) + "…"
    }

    private static func trimSlash(_ url: String) -> String {
        url.hasSuffix("/") ? String(url.dropLast()) : url
    }
}
