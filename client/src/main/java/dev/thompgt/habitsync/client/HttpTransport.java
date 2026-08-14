package dev.thompgt.habitsync.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thompgt.habitsync.sync.Change;
import dev.thompgt.habitsync.sync.ChangeCodec;
import dev.thompgt.habitsync.sync.SequencedChange;
import dev.thompgt.habitsync.sync.SyncRequest;
import dev.thompgt.habitsync.sync.SyncResponse;
import dev.thompgt.habitsync.sync.Transport;
import dev.thompgt.habitsync.sync.TransportException;
import dev.thompgt.habitsync.sync.WireChange;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The real transport: HTTP to {@code /v1/sync}, bearer tokens, and refresh-on-401.
 *
 * <p>{@link dev.thompgt.habitsync.sync.SyncEngine} knows none of this. Everything below is
 * what {@link Transport}'s one method exists to hide, which is why the same engine runs
 * unchanged against this and against the simulator's in-process network.
 *
 * <h2>Retryable versus not, and why it matters here</h2>
 *
 * The engine treats a {@link TransportException} as "your outbox and watermark are untouched,
 * try later". That is safe for a timeout and wrong for a 400: a malformed op retried forever
 * is a device that never syncs again and never says why. So failures are classified rather
 * than blanket-thrown — anything the server could plausibly answer differently next time is
 * retryable, and anything describing a defect in the request is not.
 *
 * <p>A lost response is deliberately indistinguishable from a lost request here, because it is
 * indistinguishable in reality. Both surface as a retryable failure, the engine re-pushes, and
 * the server's op-id idempotency makes the replay a no-op. That path is exercised thousands of
 * times per CI run by the M6 simulator; this class simply must not get in its way by, for
 * example, clearing anything optimistically.
 *
 * <h2>Refresh</h2>
 *
 * A 401 triggers exactly one refresh-and-retry. Looping would turn a revoked session into an
 * infinite request storm against the auth endpoint, and the server revokes whole sessions on
 * refresh-token reuse — so a client that retried refresh in a loop would be indistinguishable
 * from the token theft that rule exists to detect.
 */
public final class HttpTransport implements Transport, AutoCloseable {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Session session;

    public HttpTransport(String baseUrl, Session session) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.session = session;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.json = defaultMapper();
    }

    /**
     * @return a mapper configured for this protocol.
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} off so a newer server may add response fields
     * without breaking deployed clients — the version gate is {@code protocolVersion}, which
     * is checked explicitly, not "did every key parse".
     *
     * <p>Note what is <em>not</em> configured: null inclusion is left at Jackson's default of
     * including nulls. A null field value means "this field was cleared", and omitting nulls
     * would delete every clear operation from the request body on its way out.
     */
    static ObjectMapper defaultMapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // -------------------------------------------------------------- protocol

    @Override
    public SyncResponse exchange(SyncRequest request) throws TransportException {
        List<WireChange> ops = new ArrayList<>(request.ops().size());
        for (Change op : request.ops()) {
            ops.add(ChangeCodec.encode(op));
        }

        SyncWire.Request body = new SyncWire.Request(request.sinceSeq(), SyncWire.PROTOCOL_VERSION, ops);
        HttpResponse<String> response = send("/v1/sync", body, true);

        if (response.statusCode() == 426) {
            throw new TransportException(
                    "Server rejected protocol version " + SyncWire.PROTOCOL_VERSION + "; this client must upgrade",
                    null,
                    false);
        }
        if (response.statusCode() != 200) {
            throw failure(response);
        }

        SyncWire.Response parsed = read(response.body(), SyncWire.Response.class);
        if (parsed.protocolVersion() != SyncWire.PROTOCOL_VERSION) {
            throw new TransportException(
                    "Server answered protocol version %d, expected %d"
                            .formatted(parsed.protocolVersion(), SyncWire.PROTOCOL_VERSION),
                    null,
                    false);
        }
        return toCore(parsed);
    }

    private SyncResponse toCore(SyncWire.Response wire) throws TransportException {
        Set<UUID> applied = new LinkedHashSet<>(
                wire.appliedOpIds() == null ? List.of() : wire.appliedOpIds());

        List<SequencedChange> changes = new ArrayList<>();
        if (wire.changes() != null) {
            for (SyncWire.ChangeEnvelope envelope : wire.changes()) {
                try {
                    // Decoded through the shared codec, so this client accepts exactly what
                    // the server accepts. A change the server would have rejected is one this
                    // rejects too, rather than one it silently mis-applies.
                    changes.add(new SequencedChange(envelope.serverSeq(), ChangeCodec.decode(envelope.change())));
                } catch (IllegalArgumentException e) {
                    // Not retryable: the same bytes will fail identically forever, and the
                    // watermark must not advance past a change that was never applied.
                    throw new TransportException("Server sent a change this client cannot decode", e, false);
                }
            }
        }

        return new SyncResponse(
                applied,
                changes,
                wire.nextSeq(),
                wire.hasMore(),
                wire.resyncRequired(),
                wire.resyncReason(),
                wire.serverTimeMillis());
    }

    // ------------------------------------------------------------------ auth

    /** Registers a new account and returns its session. */
    public static Session register(String baseUrl, String email, String password, String deviceName)
            throws TransportException {
        return authCall(baseUrl, "/v1/auth/register", new SyncWire.RegisterRequest(email, password, deviceName))
                .toSession();
    }

    /**
     * Logs in, keeping {@code existingDeviceId} if the caller has one.
     *
     * <p>Passing it is not an optimisation. The device id is the HLC node id; taking a new one
     * on every login splits one device's causal history across several identities, and the
     * nodeId tiebreak that makes HLC ordering total stops distinguishing what it was there to
     * distinguish.
     */
    public static Session login(
            String baseUrl, String email, String password, String deviceName, UUID existingDeviceId)
            throws TransportException {
        return authCall(baseUrl, "/v1/auth/login", new SyncWire.LoginRequest(email, password, deviceName, existingDeviceId))
                .toSession();
    }

    private static SyncWire.TokenResponse authCall(String baseUrl, String path, Object body)
            throws TransportException {
        ObjectMapper mapper = defaultMapper();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new TransportException(
                        "%s failed: HTTP %d %s".formatted(path, response.statusCode(), response.body()),
                        null,
                        response.statusCode() / 100 == 5);
            }
            return mapper.readValue(response.body(), SyncWire.TokenResponse.class);
        } catch (IOException e) {
            throw new TransportException(path + " failed", e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(path + " interrupted", e, true);
        }
    }

    /**
     * Exchanges the refresh token for a new pair, storing both.
     *
     * <p>Both, always. The server rotates on every refresh and treats a re-presented token as
     * theft, revoking the whole account's sessions — so dropping the new refresh token on the
     * floor here would log the user out of every device on the next attempt.
     */
    private boolean refresh() {
        try {
            HttpResponse<String> response = send(
                    "/v1/auth/refresh", new SyncWire.RefreshRequest(session.refreshToken()), false);
            if (response.statusCode() / 100 != 2) {
                return false;
            }
            SyncWire.TokenResponse token = read(response.body(), SyncWire.TokenResponse.class);
            session.replaceTokens(token.accessToken(), token.refreshToken());
            return true;
        } catch (TransportException e) {
            return false;
        }
    }

    // -------------------------------------------------------------- plumbing

    private HttpResponse<String> send(String path, Object body, boolean authenticated) throws TransportException {
        HttpResponse<String> response = sendOnce(path, body, authenticated);
        if (authenticated && response.statusCode() == 401 && refresh()) {
            // Exactly one retry. See the class comment: looping against a revoked session
            // would be indistinguishable from the token theft the server watches for.
            response = sendOnce(path, body, true);
        }
        return response;
    }

    private HttpResponse<String> sendOnce(String path, Object body, boolean authenticated)
            throws TransportException {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUri + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (authenticated) {
                request.header("Authorization", "Bearer " + session.accessToken());
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // A timeout or a dropped connection. The server may or may not have committed --
            // that ambiguity is the whole reason push is idempotent -- so this is retryable.
            throw new TransportException(path + " failed", e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(path + " interrupted", e, true);
        }
    }

    private <T> T read(String body, Class<T> type) throws TransportException {
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            throw new TransportException("Malformed response body", e, false);
        }
    }

    /**
     * Classifies a non-200 into retryable or not.
     *
     * <p>5xx and 429 are the server's problem and may resolve; 4xx describes this request and
     * will not. Retrying a 400 forever is how a device with one poison op stops syncing
     * permanently while reporting nothing but "sync failed".
     */
    private static TransportException failure(HttpResponse<String> response) {
        int status = response.statusCode();
        boolean retryable = status / 100 == 5 || status == 429;
        return new TransportException(
                "/v1/sync failed: HTTP %d %s".formatted(status, response.body()), null, retryable);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public void close() {
        http.close();
    }
}
