package dev.thompgt.habitsync.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies stateless access tokens.
 *
 * <p>Access tokens are short-lived and <b>not</b> revocable — there is no server-side
 * session to invalidate. Revocation lives entirely on the refresh side, where tokens are
 * stored, hashed, and can be killed. That is the usual trade: stateless verification on
 * the hot path (every sync request) in exchange for a bounded window during which a
 * stolen access token still works.
 */
@Service
public class JwtService {

    private static final String CLAIM_DEVICE_ID = "did";

    private final SecretKey key;
    private final SecurityProperties properties;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(UUID userId, UUID deviceId, Instant now) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_DEVICE_ID, deviceId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies a token and extracts its principal.
     *
     * <p>Returns empty rather than throwing for <em>any</em> invalid token — bad
     * signature, expired, malformed, wrong claims. Callers cannot act differently on the
     * distinction, and reporting it back would tell an attacker which part of a forged
     * token to fix.
     */
    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String deviceId = claims.get(CLAIM_DEVICE_ID, String.class);
            if (deviceId == null) {
                return Optional.empty();
            }
            return Optional.of(
                    new AuthenticatedUser(UUID.fromString(claims.getSubject()), UUID.fromString(deviceId)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
