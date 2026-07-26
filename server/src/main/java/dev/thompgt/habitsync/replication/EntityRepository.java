package dev.thompgt.habitsync.replication;

import dev.thompgt.habitsync.sync.Hlc;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Loads every entity this user has, tombstones included.
     *
     * <p>Backs the bootstrap snapshot. Tombstones are deliberately in scope: a device that
     * rebuilt from a snapshot omitting them would show entities that were deleted, and its
     * next local edit to one would look, to a reader, like a resurrection.
     *
     * <p>Two queries and a join in memory rather than one SQL join, matching
     * {@link #load}: an entity with no field rows yet — created by a DELETE that arrived
     * before its UPSERT — must still appear.
     */
    public List<StoredEntityRow> loadAll(UUID userId) {
        Map<Key, StoredEntityRow> byKey = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT entity_type, entity_id, deleted, lifecycle_hlc FROM entity
                 WHERE user_id = ?
                 ORDER BY entity_type, entity_id
                """,
                rs -> {
                    Key key = new Key(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class));
                    byKey.put(
                            key,
                            new StoredEntityRow(
                                    key.entityType(),
                                    key.entityId(),
                                    new StoredEntity(
                                            rs.getBoolean("deleted"),
                                            rs.getString("lifecycle_hlc"),
                                            new LinkedHashMap<>(),
                                            new LinkedHashMap<>())));
                },
                userId);

        // Mutable accumulators, then a rebuild: StoredEntity copies its maps defensively,
        // so fields cannot be added to one after construction.
        Map<Key, Map<String, String>> values = new LinkedHashMap<>();
        Map<Key, Map<String, String>> clocks = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT entity_type, entity_id, field, value, hlc FROM entity_field
                 WHERE user_id = ?
                """,
                rs -> {
                    Key key = new Key(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class));
                    values.computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .put(rs.getString("field"), rs.getString("value"));
                    clocks.computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .put(rs.getString("field"), rs.getString("hlc"));
                },
                userId);

        List<StoredEntityRow> rows = new java.util.ArrayList<>(byKey.size());
        byKey.forEach((key, row) -> rows.add(new StoredEntityRow(
                row.entityType(),
                row.entityId(),
                new StoredEntity(
                        row.entity().deleted(),
                        row.entity().lifecycleHlc(),
                        values.getOrDefault(key, Map.of()),
                        clocks.getOrDefault(key, Map.of())))));
        return rows;
    }

    private record Key(String entityType, UUID entityId) {}

    /** An entity together with the identity it is stored under. */
    public record StoredEntityRow(String entityType, UUID entityId, StoredEntity entity) {}

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
     * Hard-deletes tombstoned entities untouched since {@code cutoff} (ADR-003).
     *
     * <p>{@code entity_field} rows go with them by cascade. This is the one place the
     * system deletes anything physically, and it is safe only because a device that could
     * still be carrying an edit to one of these is, by the same cutoff, too far behind to
     * be served incrementally and will be told to resync.
     *
     * <p>Accepted residual risk, stated rather than hidden: a device offline for longer
     * than the retention window pushes its outbox <em>before</em> wiping for the resync, so
     * an edit it queued against an entity whose tombstone has since been collected creates
     * that entity afresh. That is a resurrection. It needs a device offline past the window
     * holding an unpushed edit to an entity deleted before it went offline, and the
     * alternative — retaining every tombstone forever — is the unbounded growth the
     * retention window exists to stop.
     *
     * @return entities removed
     */
    public int deleteTombstonesOlderThan(UUID userId, java.time.Instant cutoff) {
        return jdbc.update(
                """
                DELETE FROM entity
                 WHERE user_id = ? AND deleted AND updated_at < ?
                """,
                userId,
                java.sql.Timestamp.from(cutoff));
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
