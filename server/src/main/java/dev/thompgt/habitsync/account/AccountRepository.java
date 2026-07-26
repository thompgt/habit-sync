package dev.thompgt.habitsync.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Users, devices, and the per-user sync counter.
 *
 * <p>Plain {@link JdbcTemplate} rather than JPA. The sync path needs exact control over
 * statement shape and locking — {@code SELECT ... FOR UPDATE} in a specific transaction,
 * batch inserts into {@code change_log} — and an ORM's job is to hide precisely those
 * details. Explicit SQL is also self-documenting against the ADRs.
 */
@Repository
public class AccountRepository {

    private static final RowMapper<AppUser> USER_MAPPER = (rs, rowNum) -> new AppUser(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Device> DEVICE_MAPPER = (rs, rowNum) -> new Device(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("display_name"),
            rs.getLong("last_seen_seq"),
            rs.getTimestamp("last_seen_at") == null
                    ? null
                    : rs.getTimestamp("last_seen_at").toInstant());

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ users

    /**
     * Inserts a user and their sync counter together.
     *
     * <p>The counter row is created here rather than lazily on first push, so the push
     * path can rely on {@code SELECT ... FOR UPDATE} always finding a row to lock. A
     * lazily-created counter would need an upsert under concurrency, which is exactly
     * the race ADR-002 exists to avoid.
     */
    public void insertUser(AppUser user) {
        jdbc.update(
                "INSERT INTO app_user (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
                user.id(),
                user.email(),
                user.passwordHash(),
                java.sql.Timestamp.from(user.createdAt()));
        jdbc.update("INSERT INTO user_sync_counter (user_id, next_seq) VALUES (?, 1)", user.id());
    }

    public Optional<AppUser> findByEmail(String email) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app_user WHERE lower(email) = lower(?)", USER_MAPPER, email));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AppUser> findById(UUID id) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT * FROM app_user WHERE id = ?", USER_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean emailExists(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE lower(email) = lower(?)", Integer.class, email);
        return count != null && count > 0;
    }

    // ---------------------------------------------------------------- devices

    public void insertDevice(Device device) {
        jdbc.update(
                """
                INSERT INTO device (id, user_id, display_name, last_seen_seq)
                VALUES (?, ?, ?, ?)
                """,
                device.id(),
                device.userId(),
                device.displayName(),
                device.lastSeenSeq());
    }

    public Optional<Device> findDevice(UUID userId, UUID deviceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM device WHERE user_id = ? AND id = ?", DEVICE_MAPPER, userId, deviceId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Device> findDevices(UUID userId) {
        return jdbc.query("SELECT * FROM device WHERE user_id = ? ORDER BY created_at", DEVICE_MAPPER, userId);
    }

    /**
     * Devices whose watermark sits below {@code seq} — each one a forced resync.
     *
     * <p>Reported by the retention sweep, not obeyed by it. Bounding collection by the
     * slowest device would let one lost phone pin an account's log forever, which is the
     * unbounded growth retention exists to prevent. ADR-003 makes the pull-side horizon
     * check the safety property and the window the policy; this is how an operator sees
     * the policy's cost before users do.
     */
    public List<Device> findDevicesBehind(UUID userId, long seq) {
        return jdbc.query(
                "SELECT * FROM device WHERE user_id = ? AND last_seen_seq < ?", DEVICE_MAPPER, userId, seq);
    }

    /**
     * Records how far a device has durably synced.
     *
     * <p>Monotonic by construction — {@code GREATEST} means an out-of-order or replayed
     * request can never move a device's watermark backwards. That matters because this
     * value bounds tombstone GC (ADR-003): a rewound watermark would let GC delete
     * tombstones the device has in fact already seen, or, worse, make the horizon check
     * pass for a device that is actually behind.
     */
    public void recordDeviceProgress(UUID userId, UUID deviceId, long seenSeq, Instant at) {
        jdbc.update(
                """
                UPDATE device
                   SET last_seen_seq = GREATEST(last_seen_seq, ?),
                       last_seen_at = ?
                 WHERE user_id = ? AND id = ?
                """,
                seenSeq,
                java.sql.Timestamp.from(at),
                userId,
                deviceId);
    }
}
