package dev.thompgt.habitsync;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the Flyway migration actually applies to a real Postgres, and pins the
 * invariants the sync layer will rely on later.
 *
 * <p>These read like trivia until one of them breaks. A missing unique index on
 * {@code (user_id, op_id)} does not fail a build — it silently turns idempotent retries
 * into double-applied writes, months later, in production.
 */
class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationCreatesEveryExpectedTable() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables)
                .contains(
                        "app_user",
                        "device",
                        "refresh_token",
                        "user_sync_counter",
                        "entity",
                        "entity_field",
                        "change_log");
    }

    @Test
    @DisplayName("(user_id, op_id) is unique — this is what makes push retries idempotent")
    void changeLogEnforcesOpIdUniqueness() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM pg_indexes
                 WHERE tablename = 'change_log' AND indexname = 'ux_change_log_op'
                """,
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("change_log is keyed by (user_id, server_seq) so incremental pull is an index scan")
    void changeLogPrimaryKeyOrdersByUserThenSeq() {
        List<String> columns = jdbc.queryForList(
                """
                SELECT a.attname
                  FROM pg_index i
                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                 WHERE i.indrelid = 'change_log'::regclass AND i.indisprimary
                 ORDER BY array_position(i.indkey, a.attnum)
                """,
                String.class);

        assertThat(columns).containsExactly("user_id", "server_seq");
    }

    @Test
    @DisplayName("a deleted entity must carry a lifecycle HLC, or merge cannot order the tombstone")
    void entityCheckConstraintRejectsDeletedWithoutLifecycleHlc() {
        var userId = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, email, password_hash) VALUES (?, ?, ?)",
                userId,
                "constraint-test@example.com",
                "x");

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> jdbc.update(
                                        """
                                        INSERT INTO entity (user_id, entity_type, entity_id, deleted, lifecycle_hlc)
                                        VALUES (?, 'HABIT', ?, TRUE, NULL)
                                        """,
                                        userId,
                                        java.util.UUID.randomUUID())))
                .isNotNull();
    }

    @Test
    @DisplayName("entity_field.value distinguishes 'cleared' from 'never written'")
    void entityFieldAllowsNullValues() {
        var userId = java.util.UUID.randomUUID();
        var entityId = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, email, password_hash) VALUES (?, ?, ?)",
                userId,
                "nullable-test@example.com",
                "x");
        jdbc.update(
                "INSERT INTO entity (user_id, entity_type, entity_id) VALUES (?, 'HABIT', ?)",
                userId,
                entityId);

        // A cleared field is a row with a NULL value, not an absent row.
        jdbc.update(
                """
                INSERT INTO entity_field (user_id, entity_type, entity_id, field, value, hlc)
                VALUES (?, 'HABIT', ?, 'colour', NULL, '1000:0:node-a')
                """,
                userId,
                entityId);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM entity_field WHERE entity_id = ? AND value IS NULL",
                Integer.class,
                entityId);
        assertThat(rows).isEqualTo(1);
    }
}
