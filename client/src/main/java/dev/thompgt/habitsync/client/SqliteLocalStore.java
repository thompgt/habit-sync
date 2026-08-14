package dev.thompgt.habitsync.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thompgt.habitsync.sync.Change;
import dev.thompgt.habitsync.sync.ChangeCodec;
import dev.thompgt.habitsync.sync.EntityKey;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import dev.thompgt.habitsync.sync.Hlc;
import dev.thompgt.habitsync.sync.LocalStore;
import dev.thompgt.habitsync.sync.WireChange;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A durable {@link LocalStore} on SQLite — the device's disk.
 *
 * <p>{@link dev.thompgt.habitsync.sync.InMemoryLocalStore} satisfies the same interface and is
 * what the simulator uses, but it satisfies the atomicity contract by holding a monitor, which
 * is exactly as strong as a single process and no stronger. The contract is about surviving
 * process death, and only a transaction on disk provides that. This class is where that claim
 * is actually cashed.
 *
 * <h2>The two transactions that matter</h2>
 *
 * <ul>
 *   <li>{@link #applyLocal} writes the merged entity <b>and</b> enqueues the outbox op in one
 *       commit. Split them, and a crash in between leaves the user's edit on screen and
 *       nowhere else — silent data loss, discovered weeks later when a second device never
 *       shows it.
 *   <li>{@link #applyRemote} writes the page's entities <b>and</b> advances the watermark in
 *       one commit. Advancing first would let a crash skip that range permanently, because the
 *       device never asks for it again.
 * </ul>
 *
 * <p>{@code synchronous=FULL}, not SQLite's default of NORMAL. NORMAL under WAL can lose the
 * tail of recently committed transactions on power loss, which is precisely the failure this
 * class exists to prevent — and the write rate here is a handful of transactions per sync, so
 * the cost is irrelevant.
 *
 * <h2>Why the clock is stored here and not saved separately</h2>
 *
 * {@link LocalStore#lastClock()} is derived from the writes this store already accepts, in the
 * same transaction as the change that used it. A separate {@code saveClock} call could land on
 * the other side of a crash from the op it stamped, and the device would then reissue an HLC
 * it had already used — two different writes with identical timestamps, which is the one way a
 * client can break convergence, because merge's strictly-greater rule leaves replicas that see
 * them in different orders free to pick different winners.
 */
public final class SqliteLocalStore implements LocalStore, AutoCloseable {

    private static final String KEY_WATERMARK = "watermark";
    private static final String KEY_LAST_CLOCK = "lastClock";

    private final Connection connection;
    private final ObjectMapper json = HttpTransport.defaultMapper();

    public SqliteLocalStore(String databasePath) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            // Pragmas and DDL first, while the connection is still in autocommit. SQLite
            // refuses to change the safety level inside a transaction, and setAutoCommit(false)
            // opens one immediately -- so the order here is load-bearing, not stylistic.
            initialise();
            this.connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the local store at " + databasePath, e);
        }
    }

    /** An in-memory database, for tests. Durability is absent by definition; atomicity is not. */
    public static SqliteLocalStore inMemory() {
        return new SqliteLocalStore(":memory:");
    }

    private void initialise() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS entity (
                        entity_type   TEXT NOT NULL,
                        entity_id     TEXT NOT NULL,
                        deleted       INTEGER NOT NULL DEFAULT 0,
                        lifecycle_hlc TEXT,
                        PRIMARY KEY (entity_type, entity_id)
                    )
                    """);
            // One row per field, mirroring the server's entity_field and sync-core's per-field
            // registers. A NULL value is a cleared field and is distinct from no row at all.
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS entity_field (
                        entity_type TEXT NOT NULL,
                        entity_id   TEXT NOT NULL,
                        field       TEXT NOT NULL,
                        value       TEXT,
                        hlc         TEXT NOT NULL,
                        PRIMARY KEY (entity_type, entity_id, field),
                        FOREIGN KEY (entity_type, entity_id)
                            REFERENCES entity (entity_type, entity_id) ON DELETE CASCADE
                    )
                    """);
            // seq gives the outbox a stable oldest-first order. Ordering is a courtesy to the
            // server's logs rather than a correctness requirement -- merge is commutative --
            // but an unordered outbox makes a device's own history unreadable when debugging.
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS outbox (
                        seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                        op_id   TEXT NOT NULL UNIQUE,
                        payload TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    public synchronized Optional<EntityRecord> load(EntityKey key) {
        try {
            return loadWithin(key);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load " + key, e);
        }
    }

    private Optional<EntityRecord> loadWithin(EntityKey key) throws SQLException {
        boolean deleted;
        Hlc lifecycle;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT deleted, lifecycle_hlc FROM entity WHERE entity_type = ? AND entity_id = ?")) {
            statement.setString(1, key.type().name());
            statement.setString(2, key.id().toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                deleted = rs.getInt(1) != 0;
                String raw = rs.getString(2);
                lifecycle = raw == null ? null : Hlc.parse(raw);
            }
        }

        Map<String, FieldValue> fields = new LinkedHashMap<>();
        Map<String, Hlc> clocks = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT field, value, hlc FROM entity_field WHERE entity_type = ? AND entity_id = ? ORDER BY field")) {
            statement.setString(1, key.type().name());
            statement.setString(2, key.id().toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    fields.put(rs.getString(1), FieldValue.of(rs.getString(2)));
                    clocks.put(rs.getString(1), Hlc.parse(rs.getString(3)));
                }
            }
        }

        return Optional.of(new EntityRecord(key.type(), key.id(), fields, clocks, deleted, lifecycle));
    }

    @Override
    public synchronized long watermark() {
        return Long.parseLong(meta(KEY_WATERMARK).orElse("0"));
    }

    @Override
    public synchronized Optional<Hlc> lastClock() {
        return meta(KEY_LAST_CLOCK).map(Hlc::parse);
    }

    @Override
    public synchronized List<Change> pendingOps(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        List<Change> ops = new ArrayList<>();
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT payload FROM outbox ORDER BY seq LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ops.add(ChangeCodec.decode(json.readValue(rs.getString(1), WireChange.class)));
                }
            }
            return List.copyOf(ops);
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to read the outbox", e);
        }
    }

    @Override
    public synchronized int pendingOpCount() {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM outbox")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count the outbox", e);
        }
    }

    // ----------------------------------------------------------------- writes

    @Override
    public synchronized void applyLocal(EntityRecord merged, Change op) {
        if (!merged.key().equals(op.key())) {
            throw new IllegalArgumentException(
                    "Record %s does not match op target %s".formatted(merged.key(), op.key()));
        }
        inTransaction(() -> {
            writeEntity(merged);
            enqueue(op);
            // Derived from the op, in the same commit, so the clock cannot outlive or trail
            // the change that used it.
            advanceClock(op.hlc());
        });
    }

    @Override
    public synchronized void applyRemote(Collection<EntityRecord> merged, long nextSeq, Hlc clock) {
        long current = watermark();
        if (nextSeq < current) {
            throw new IllegalArgumentException("Watermark must not go backwards: %d -> %d".formatted(current, nextSeq));
        }
        inTransaction(() -> {
            for (EntityRecord record : merged) {
                writeEntity(record);
            }
            // Same commit as the entities above. This is the ordering LocalStore's contract
            // exists for: a watermark ahead of the state it describes is unrecoverable.
            putMeta(KEY_WATERMARK, Long.toString(nextSeq));
            advanceClock(clock);
        });
    }

    @Override
    public synchronized void acknowledgeOps(Collection<UUID> opIds) {
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM outbox WHERE op_id = ?")) {
                for (UUID opId : opIds) {
                    statement.setString(1, opId.toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    @Override
    public synchronized void resetForResync() {
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM entity_field");
                statement.execute("DELETE FROM entity");
            }
            putMeta(KEY_WATERMARK, "0");
            // The outbox and lastClock deliberately survive. Those ops are the device's own
            // un-pushed work and the server's retention policy is no reason to bin them; the
            // clock is this device's monotonic identity, and resetting it would let the device
            // reissue timestamps it has already used.
        });
    }

    // -------------------------------------------------------------- plumbing

    private void writeEntity(EntityRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO entity (entity_type, entity_id, deleted, lifecycle_hlc)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (entity_type, entity_id)
                DO UPDATE SET deleted = excluded.deleted, lifecycle_hlc = excluded.lifecycle_hlc
                """)) {
            statement.setString(1, record.type().name());
            statement.setString(2, record.id().toString());
            statement.setInt(3, record.deleted() ? 1 : 0);
            statement.setString(4, record.lifecycleClock() == null ? null : record.lifecycleClock().toCompactString());
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO entity_field (entity_type, entity_id, field, value, hlc)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (entity_type, entity_id, field)
                DO UPDATE SET value = excluded.value, hlc = excluded.hlc
                """)) {
            for (Map.Entry<String, FieldValue> field : record.fields().entrySet()) {
                statement.setString(1, record.type().name());
                statement.setString(2, record.id().toString());
                statement.setString(3, field.getKey());
                // setString(null) writes SQL NULL, which is the cleared-field representation.
                statement.setString(4, field.getValue().raw());
                statement.setString(5, record.clockOf(field.getKey()).toCompactString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void enqueue(Change op) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO outbox (op_id, payload) VALUES (?, ?)")) {
            statement.setString(1, op.opId().toString());
            statement.setString(2, encode(op));
            statement.executeUpdate();
        }
    }

    private String encode(Change op) {
        try {
            return json.writeValueAsString(ChangeCodec.encode(op));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode op " + op.opId(), e);
        }
    }

    /** Keeps {@code lastClock} at the highest reading this store has ever committed. */
    private void advanceClock(Hlc observed) throws SQLException {
        Hlc previous = meta(KEY_LAST_CLOCK).map(Hlc::parse).orElse(null);
        putMeta(KEY_LAST_CLOCK, Hlc.max(previous, observed).toCompactString());
    }

    private Optional<String> meta(String key) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read meta key " + key, e);
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    /**
     * Runs {@code body} in one transaction, rolling back on any failure.
     *
     * <p>The rollback is the point. A half-applied {@link #applyLocal} is the corruption this
     * class exists to rule out, and "we would have noticed" is not a mechanism.
     */
    private void inTransaction(SqlBody body) {
        try {
            body.run();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("Local store write failed and was rolled back", e);
        }
    }

    @FunctionalInterface
    private interface SqlBody {
        void run() throws SQLException;
    }

    // --------------------------------------------------------- inspection

    /** @return every entity this device holds, tombstones included. */
    public synchronized List<EntityRecord> allRecords() {
        List<EntityKey> keys = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT entity_type, entity_id FROM entity ORDER BY entity_type, entity_id")) {
            while (rs.next()) {
                keys.add(new EntityKey(EntityType.valueOf(rs.getString(1)), UUID.fromString(rs.getString(2))));
            }
            List<EntityRecord> records = new ArrayList<>(keys.size());
            for (EntityKey key : keys) {
                loadWithin(key).ifPresent(records::add);
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list entities", e);
        }
    }

    /** @return the entities the application would show the user. */
    public synchronized List<EntityRecord> visibleRecords() {
        return allRecords().stream().filter(EntityRecord::visible).toList();
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close the local store", e);
        }
    }
}
