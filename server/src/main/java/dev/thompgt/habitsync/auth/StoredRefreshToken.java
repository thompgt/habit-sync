package dev.thompgt.habitsync.auth;

import java.time.Instant;
import java.util.UUID;

public record StoredRefreshToken(
        UUID id, UUID userId, UUID deviceId, Instant expiresAt, Instant revokedAt, UUID replacedBy) {

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * A revoked token that names a successor was rotated normally. Presenting it again
     * means someone kept a copy — see {@link RefreshTokenRepository#revokeAllForUser}.
     */
    public boolean wasRotated() {
        return replacedBy != null;
    }
}
