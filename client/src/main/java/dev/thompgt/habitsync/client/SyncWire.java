package dev.thompgt.habitsync.client;

import dev.thompgt.habitsync.sync.WireChange;
import java.util.List;
import java.util.UUID;

/**
 * The JSON bodies of {@code /v1/sync}, as this client sends and expects them.
 *
 * <p>A separate declaration from the server's {@code SyncDtos} rather than a shared one, and
 * that is a real trade-off rather than an oversight. Sharing would guarantee the two agree;
 * it would also drag the client's dependencies onto whatever the server's DTO module happens
 * to need, and would make a protocol change look source-compatible when it is not. What is
 * shared is the part where drift actually costs data — {@link WireChange} and its codec live
 * in {@code sync-core} and both ends encode through them.
 *
 * <p>The envelope around it is small enough to be checked by an end-to-end test, and there is
 * one: if a field name here stops matching the server's, that test fails rather than a device
 * silently syncing nothing.
 */
final class SyncWire {

    private SyncWire() {}

    /** Must match the server's {@code SyncDtos.PROTOCOL_VERSION}, or it answers 426. */
    static final int PROTOCOL_VERSION = 1;

    record Request(long sinceSeq, int protocolVersion, List<WireChange> ops) {}

    record Response(
            List<UUID> appliedOpIds,
            List<ChangeEnvelope> changes,
            long nextSeq,
            boolean hasMore,
            boolean resyncRequired,
            String resyncReason,
            long serverTimeMillis,
            int protocolVersion) {}

    record ChangeEnvelope(long serverSeq, WireChange change) {}

    record LoginRequest(String email, String password, String deviceName, UUID deviceId) {}

    record RegisterRequest(String email, String password, String deviceName) {}

    record RefreshRequest(String refreshToken) {}

    record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            UUID userId,
            UUID deviceId) {

        Session toSession() {
            return new Session(userId, deviceId, accessToken, refreshToken);
        }
    }
}
