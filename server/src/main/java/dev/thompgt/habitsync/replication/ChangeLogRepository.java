package dev.thompgt.habitsync.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thompgt.habitsync.sync.WireChange;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The replication log: allocation of {@code server_seq}, append, and incremental read.
 *
 * <p>This class is the sole owner of {@code change_log} writes and the sole caller of
 * the sequence allocator. That is not a style preference — see ADR-002. Any second write
 * path that assigns a {@code server_seq} outside {@link #allocateSequenceRange} silently
 * reintroduces a watermark bug that loses changes permanently.
 */
@Repository
public class ChangeLogRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ChangeLogRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Reserves {@code count} consecutive sequence numbers for a user and returns the
     * first.
     *
     * <p><b>Must be called inside the same transaction as the subsequent insert.</b> The
     * {@code FOR UPDATE} takes a row lock on the user's counter which is held until that
     * transaction commits, serialising all of that user's concurrent pushes. This is what
     * makes sequence order equal commit order, and therefore what makes
     * {@code server_seq > watermark} a sound incremental-read predicate.
     *
     * <p>A plain {@code BIGSERIAL} does not have this property: sequence values are
     * allocated at insert time but become visible at commit time, so a slow transaction
     * holding seq 5 can commit after a fast one holding seq 6. A client that pulls in
     * between advances its watermark past 5 and never sees it again.
     *
     * <p>Contention is per-user — two or three devices — so serialising here costs
     * nothing. Different users never block one another.
     */
    public long allocateSequenceRange(UUID userId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, got " + count);
        }

        Long next = jdbc.queryForObject(
                "SELECT next_seq FROM user_sync_counter WHERE user_id = ? FOR UPDATE", Long.class, userId);
        if (next == null) {
            throw new IllegalStateException(
                    "No sync counter for user " + userId + "; it is created at registration");
        }

        jdbc.update("UPDATE user_sync_counter SET next_seq = next_seq + ? WHERE user_id = ?", count, userId);
        return next;
    }

    /** @return the highest sequence allocated so far, or 0 if the user has never pushed. */
    public long currentSequence(UUID userId) {
        Long next = jdbc.queryForObject(
                "SELECT next_seq FROM user_sync_counter WHERE user_id = ?", Long.class, userId);
        return next == null ? 0L : next - 1;
    }

    /**
     * Appends changes at the given starting sequence.
     *
     * <p>Batched, and ordered so that the nth change takes {@code startSeq + n}.
     */
    public void append(UUID userId, long startSeq, List<WireChange> changes, String originNode) {
        jdbc.batchUpdate(
                """
                INSERT INTO change_log
                    (user_id, server_seq, op_id, entity_type, entity_id, kind, hlc, payload, origin_node)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                        WireChange change = changes.get(i);
                        ps.setObject(1, userId);
                        ps.setLong(2, startSeq + i);
                        ps.setObject(3, change.opId());
                        ps.setString(4, change.entityType());
                        ps.setObject(5, change.entityId());
                        ps.setString(6, change.kind());
                        ps.setString(7, change.hlc());
                        ps.setString(8, writeFields(change.fields()));
                        ps.setString(9, originNode);
                    }

                    @Override
                    public int getBatchSize() {
                        return changes.size();
                    }
                });
    }

    /**
     * Reads one page of changes strictly after {@code sinceSeq}.
     *
     * <p>Served directly by the {@code (user_id, server_seq)} primary key, so an
     * incremental pull is an index range scan regardless of log size.
     */
    public List<LoggedChange> readSince(UUID userId, long sinceSeq, int limit) {
        return jdbc.query(
                """
                SELECT server_seq, op_id, entity_type, entity_id, kind, hlc, payload, origin_node
                  FROM change_log
                 WHERE user_id = ? AND server_seq > ?
                 ORDER BY server_seq
                 LIMIT ?
                """,
                changeMapper(),
                userId,
                sinceSeq,
                limit);
    }

    /**
     * Returns which of the given op ids this user has already had accepted.
     *
     * <p>The basis of idempotent push: a request that timed out after the server
     * committed is replayed by the client, and the ops it already applied are filtered
     * out here rather than double-applied.
     */
    public java.util.Set<UUID> findAlreadyApplied(UUID userId, List<UUID> opIds) {
        if (opIds.isEmpty()) {
            return java.util.Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(opIds.size(), "?"));
        Object[] args = new Object[opIds.size() + 1];
        args[0] = userId;
        for (int i = 0; i < opIds.size(); i++) {
            args[i + 1] = opIds.get(i);
        }
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT op_id FROM change_log WHERE user_id = ? AND op_id IN (" + placeholders + ")",
                UUID.class,
                args));
    }

    /** @return the sequence of the oldest retained change, used for the GC horizon check. */
    public Optional<Long> oldestRetainedSequence(UUID userId) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT min(server_seq) FROM change_log WHERE user_id = ?", Long.class, userId));
    }

    private RowMapper<LoggedChange> changeMapper() {
        return (rs, rowNum) -> new LoggedChange(
                rs.getLong("server_seq"),
                new WireChange(
                        rs.getObject("op_id", UUID.class),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("hlc"),
                        readFields(rs.getString("payload"))),
                rs.getString("origin_node"));
    }

    private String writeFields(Map<String, String> fields) {
        try {
            return json.writeValueAsString(fields == null ? Map.of() : fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise change payload", e);
        }
    }

    private Map<String, String> readFields(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            // LinkedHashMap, and NOT Map.copyOf: a cleared field is stored as a JSON null
            // and must survive the round trip. Map.of/copyOf reject null values outright,
            // which would turn "clear this field" into a deserialisation crash.
            return json.readValue(
                    payload,
                    json.getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt change payload in change_log: " + payload, e);
        }
    }

    /** A change as stored, paired with the sequence the server assigned it. */
    public record LoggedChange(long serverSeq, WireChange change, String originNode) {}
}
