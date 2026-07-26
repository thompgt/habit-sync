package dev.thompgt.habitsync.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Storage for refresh tokens. Tokens are only ever stored and looked up by hash. */
@Repository
public class RefreshTokenRepository {

    private static final RowMapper<StoredRefreshToken> MAPPER = (rs, rowNum) -> new StoredRefreshToken(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("device_id", UUID.class),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
            rs.getObject("replaced_by", UUID.class));

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID userId, UUID deviceId, String tokenHash, Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO refresh_token (id, user_id, device_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                userId,
                deviceId,
                tokenHash,
                java.sql.Timestamp.from(expiresAt));
    }

    public Optional<StoredRefreshToken> findByHash(String tokenHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM refresh_token WHERE token_hash = ?", MAPPER, tokenHash));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Marks a token as rotated into its successor. */
    public void markRotated(UUID id, UUID replacedBy, Instant at) {
        jdbc.update(
                "UPDATE refresh_token SET revoked_at = ?, replaced_by = ? WHERE id = ?",
                java.sql.Timestamp.from(at),
                replacedBy,
                id);
    }

    /**
     * Revokes every live token for a user.
     *
     * <p>Invoked on refresh-token reuse. Reuse means the same token was presented twice,
     * and since a legitimate client discards a token the moment it rotates, the second
     * presentation is either an attacker replaying a stolen token or a client racing
     * itself. Neither can be distinguished from the other, so the safe response is to
     * end every session and force a fresh login.
     */
    public int revokeAllForUser(UUID userId, Instant at) {
        return jdbc.update(
                "UPDATE refresh_token SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                java.sql.Timestamp.from(at),
                userId);
    }
}
