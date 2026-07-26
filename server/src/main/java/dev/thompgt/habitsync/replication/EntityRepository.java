package dev.thompgt.habitsync.replication;

import dev.thompgt.habitsync.sync.Hlc;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Loads and stores merged entity state as {@code sync-core} models it.
 *
 * <p>Storage is generic — {@code entity} plus {@code entity_field} — rather than typed
 * per-domain tables, so this class is the only mapping code in the server and adding a
 * new entity type requires no change here at all.
 */
@Repository
public class EntityRepository {

    private final JdbcTemplate jdbc;

    public EntityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loads an entity's full state, or empty if this user has never seen it.
     *
     * <p>Two queries rather than a join: the join would repeat the lifecycle columns
     * across every field row, and an entity with no fields yet (created by a DELETE
     * arriving before its UPSERT) would need an outer join to appear at all.
     */
    public Optional<StoredEntity> load(UUID userId, String entityType, UUID entityId) {
        var rows = jdbc.query(
                """
                SELECT deleted, lifecycle_hlc FROM entity
                 WHERE user_id = ? AND entity_type = ? AND entity_id = ?
                """,
                (rs, n) -> new Object[] {rs.getBoolean("deleted"), rs.getString("lifecycle_hlc")},
                userId,
                entityType,
                entityId);

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        boolean deleted = (Boolean) rows.get(0)[0];
        String lifecycleHlc = (String) rows.get(0)[1];

        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> clocks = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT field, value, hlc FROM entity_field
                 WHERE user_id = ? AND entity_type = ? AND entity_id = ?
                """,
                rs -> {
                    values.put(rs.getString("field"), rs.getString("value"));
                    clocks.put(rs.getString("field"), rs.getString("hlc"));
                },
                userId,
                entityType,
                entityId);

        return Optional.of(new StoredEntity(deleted, lifecycleHlc, values, clocks));
    }

    /**
     * Writes merged state back.
     *
     * <p>Upserts rather than delete-and-reinsert, so a concurrent reader never observes
     * an entity mid-write with some of its fields missing.
     */
    public void save(UUID userId, String entityType, UUID entityId, StoredEntity entity) {
        jdbc.update(
                """
                INSERT INTO entity (user_id, entity_type, entity_id, deleted, lifecycle_hlc, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id, entity_type, entity_id) DO UPDATE
                   SET deleted = EXCLUDED.deleted,
                       lifecycle_hlc = EXCLUDED.lifecycle_hlc,
                       updated_at = now()
                """,
                userId,
                entityType,
                entityId,
                entity.deleted(),
                entity.lifecycleHlc());

        for (Map.Entry<String, String> entry : entity.fieldValues().entrySet()) {
            String field = entry.getKey();
            jdbc.update(
                    """
                    INSERT INTO entity_field (user_id, entity_type, entity_id, field, value, hlc)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, entity_type, entity_id, field) DO UPDATE
                       SET value = EXCLUDED.value, hlc = EXCLUDED.hlc
                    """,
                    userId,
                    entityType,
                    entityId,
                    field,
                    entry.getValue(),
                    entity.fieldClocks().get(field));
        }
    }

    /**
     * Server-side snapshot of an entity, in the same shape as sync-core's
     * {@code EntityRecord} but with HLCs still in their compact string form.
     *
     * @param fieldValues field name to value; a {@code null} value is a cleared field,
     *                    which is distinct from an absent key
     */
    public record StoredEntity(
            boolean deleted,
            String lifecycleHlc,
            Map<String, String> fieldValues,
            Map<String, String> fieldClocks) {

        public StoredEntity {
            // HashMap copies, not Map.copyOf: cleared fields are stored as null values.
            fieldValues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fieldValues));
            fieldClocks = java.util.Collections.unmodifiableMap(new HashMap<>(fieldClocks));
        }

        public static StoredEntity empty() {
            return new StoredEntity(false, null, Map.of(), Map.of());
        }

        public Hlc lifecycleClock() {
            return lifecycleHlc == null ? null : Hlc.parse(lifecycleHlc);
        }
    }
}
