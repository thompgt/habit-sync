package dev.thompgt.habitsync.client;

import java.util.UUID;

/**
 * The device's credentials: a short-lived access token, the long-lived refresh token that
 * renews it, and the identity both belong to.
 *
 * <p>Mutable, because refresh rotation replaces both tokens and the replacement must be
 * visible to the next request. Rotation is not optional on this server: presenting a refresh
 * token that has already been exchanged is treated as evidence of theft and revokes every
 * session on the account. A client that kept the old token after refreshing would log itself
 * out, and would look like an attack while doing it.
 *
 * <p>{@code deviceId} is the load-bearing field. It doubles as the HLC node id, so it must
 * survive restarts — a device that forgets it and registers afresh fragments its own causal
 * history across two identities and loses the {@code nodeId} tiebreak that makes the clock
 * ordering total.
 */
public final class Session {

    private final UUID userId;
    private final UUID deviceId;
    private String accessToken;
    private String refreshToken;

    public Session(UUID userId, UUID deviceId, String accessToken, String refreshToken) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public UUID userId() {
        return userId;
    }

    public UUID deviceId() {
        return deviceId;
    }

    /** The HLC node id for this device. UUID text contains no ':', which Hlc forbids. */
    public String nodeId() {
        return deviceId.toString();
    }

    public synchronized String accessToken() {
        return accessToken;
    }

    public synchronized String refreshToken() {
        return refreshToken;
    }

    synchronized void replaceTokens(String access, String refresh) {
        this.accessToken = access;
        this.refreshToken = refresh;
    }
}
