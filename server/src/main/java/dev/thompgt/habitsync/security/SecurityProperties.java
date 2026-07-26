package dev.thompgt.habitsync.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings, bound from {@code security.jwt.*}.
 *
 * @param secret         HS256 signing key. Supplied from the environment; the
 *                       application refuses to start if it is missing or too short.
 * @param accessTokenTtl short-lived, because an access token cannot be revoked once
 *                       issued — the only bound on a stolen one is its expiry
 * @param refreshTokenTtl how long a device may stay logged in without re-authenticating.
 *                       Generous, because an offline-first app whose session dies during
 *                       a long offline period cannot push the work queued up behind it.
 */
@ConfigurationProperties(prefix = "security.jwt")
public record SecurityProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    /**
     * HS256 requires a key of at least 256 bits. Below that, jjwt would reject it at
     * signing time — a runtime failure on the first login rather than at startup.
     */
    private static final int MIN_SECRET_BYTES = 32;

    public SecurityProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.secret is not set. Supply it via the JWT_SECRET environment "
                            + "variable — there is deliberately no default, because a fallback key "
                            + "would silently ship to production.");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least %d bytes for HS256, got %d"
                            .formatted(MIN_SECRET_BYTES, secret.length()));
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalStateException("security.jwt.access-token-ttl must be positive");
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            throw new IllegalStateException("security.jwt.refresh-token-ttl must be positive");
        }
    }
}
