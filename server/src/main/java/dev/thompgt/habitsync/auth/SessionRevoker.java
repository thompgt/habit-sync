package dev.thompgt.habitsync.auth;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every live session for a user, in a transaction of its own.
 *
 * <p><b>{@code REQUIRES_NEW} is the entire point of this class.</b> Reuse of a rotated
 * refresh token is detected inside {@link AuthService#refresh}, which then fails the
 * request by throwing. Since that method is itself {@code @Transactional}, a revocation
 * performed in the same transaction is rolled back by the very exception that reports
 * it — the server logs "token reuse detected", returns 401, and leaves the thief's
 * session perfectly valid.
 *
 * <p>That failure is invisible without a test that asserts on database state
 * afterwards, which is why {@code RefreshRotationTest} checks that the untouched token
 * also stops working rather than just that the reused one is rejected.
 *
 * <p>Committing in a separate transaction makes the revocation durable regardless of
 * what happens to the enclosing request. This must not be "simplified" back into the
 * caller's transaction.
 */
@Service
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final RefreshTokenRepository refreshTokens;

    public SessionRevoker(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllSessions(UUID userId, Instant at, String reason) {
        int revoked = refreshTokens.revokeAllForUser(userId, at);
        log.warn("Revoked {} live session(s) for user {}: {}", revoked, userId, reason);
        return revoked;
    }
}
