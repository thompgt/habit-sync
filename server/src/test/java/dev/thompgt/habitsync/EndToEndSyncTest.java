package dev.thompgt.habitsync;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thompgt.habitsync.client.HttpTransport;
import dev.thompgt.habitsync.client.Session;
import dev.thompgt.habitsync.client.SqliteLocalStore;
import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import dev.thompgt.habitsync.sync.SyncEngine;
import dev.thompgt.habitsync.sync.TimeSource;
import dev.thompgt.habitsync.sync.TransportException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The whole thing, end to end: two devices with SQLite on disk, real HTTP, a real Postgres.
 *
 * <p>Every other test in this project verifies one layer against a stand-in for the next. The
 * simulator drives the real engine over a fake network; the server suite drives real SQL from
 * a fake client. Both are the right shape for what they test, and both leave the same gap —
 * the seams. A field name that differs between the client's request record and the server's,
 * an enum that serialises differently at the two ends, a null that one side drops: each of
 * those passes every layer's own tests and loses data in production.
 *
 * <p>So this test is deliberately about the <em>seams</em>, not about merge semantics. The
 * conflict cases here are the simplest possible ones; if they behave correctly over the wire,
 * the interesting ones are covered by the 240 simulated seeds already.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndSyncTest extends AbstractIntegrationTest {

    @LocalServerPort private int port;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * One simulated device: its own store on disk, its own transport, its own engine.
     *
     * <p>Time is real here rather than virtual. This test is not asserting anything about
     * clock ordering — the simulator does that exhaustively — and a real clock keeps the setup
     * honest about what a device actually does.
     */
    private record Device(SqliteLocalStore store, HttpTransport transport, SyncEngine engine, Session session)
            implements AutoCloseable {

        @Override
        public void close() {
            transport.close();
            store.close();
        }
    }

    private Device open(Path databaseFile, Session session) {
        SqliteLocalStore store = new SqliteLocalStore(databaseFile.toString());
        HttpTransport transport = new HttpTransport(baseUrl(), session);
        // forDevice, not a bare constructor: it restores the clock from the store, which is
        // what stops a restarted device reissuing timestamps it has already used.
        SyncEngine engine = SyncEngine.forDevice(session.nodeId(), store, transport, TimeSource.system());
        return new Device(store, transport, engine, session);
    }

    @Test
    @DisplayName("two devices converge over real HTTP, keeping both edits to one habit")
    void twoDevicesConvergeOverTheWire(@TempDir Path directory) throws TransportException {
        String email = "e2e-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery-staple";
        UUID habitId = UUID.randomUUID();

        Session sessionA = HttpTransport.register(baseUrl(), email, password, "Phone");
        Session sessionB = HttpTransport.login(baseUrl(), email, password, "Tablet", null);
        assertThat(sessionB.deviceId())
                .as("a second login without a device id must register a distinct device, or the "
                        + "two would share an HLC node id and their writes would be indistinguishable")
                .isNotEqualTo(sessionA.deviceId());

        try (Device phone = open(directory.resolve("phone.db"), sessionA);
                Device tablet = open(directory.resolve("tablet.db"), sessionB)) {

            // The phone creates a habit while offline, then reconnects.
            phone.engine().upsert(
                    EntityType.HABIT,
                    habitId,
                    java.util.Map.of(
                            "name", FieldValue.of("Run"),
                            "weeklyTarget", FieldValue.of(3),
                            "colour", FieldValue.of("red")));
            assertThat(phone.store().pendingOpCount()).isEqualTo(1);

            phone.engine().sync();
            assertThat(phone.store().pendingOpCount()).isZero();

            // The tablet has never synced, so this is a bootstrap: served from entity state,
            // one synthesised change per field, not a replay of the log.
            tablet.engine().sync();
            assertThat(tablet.engine().load(EntityType.HABIT, habitId))
                    .get()
                    .extracting(record -> record.field("name"))
                    .isEqualTo(FieldValue.of("Run"));

            // Both go offline and edit different fields of the same habit.
            phone.engine().upsert(EntityType.HABIT, habitId, "name", FieldValue.of("Jog"));
            tablet.engine().upsert(EntityType.HABIT, habitId, "weeklyTarget", FieldValue.of(5));

            // Reconnect, in an order that makes the tablet merge the phone's write and then
            // send its own on the following round trip.
            phone.engine().sync();
            tablet.engine().sync();
            phone.engine().sync();

            for (Device device : new Device[] {phone, tablet}) {
                EntityRecord habit = device.engine()
                        .load(EntityType.HABIT, habitId)
                        .orElseThrow(() -> new AssertionError(device.session().nodeId() + " lost the habit"));

                // The headline property, over the wire: per-field LWW keeps both edits. Per-row
                // LWW would silently discard one, and would look identical up to this line.
                assertThat(habit.field("name")).isEqualTo(FieldValue.of("Jog"));
                assertThat(habit.field("weeklyTarget")).isEqualTo(FieldValue.of(5));
                assertThat(habit.field("colour")).isEqualTo(FieldValue.of("red"));
                assertThat(habit.visible()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a cleared field survives the round trip as cleared, not as absent")
    void clearedFieldsSurviveTheWire(@TempDir Path directory) throws TransportException {
        String email = "e2e-null-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery-staple";
        UUID habitId = UUID.randomUUID();

        Session sessionA = HttpTransport.register(baseUrl(), email, password, "Phone");
        Session sessionB = HttpTransport.login(baseUrl(), email, password, "Tablet", null);

        try (Device phone = open(directory.resolve("phone.db"), sessionA);
                Device tablet = open(directory.resolve("tablet.db"), sessionB)) {

            phone.engine().upsert(
                    EntityType.HABIT,
                    habitId,
                    java.util.Map.of("name", FieldValue.of("Run"), "colour", FieldValue.of("red")));
            phone.engine().sync();

            // The user clears the colour. This is the case with the most ways to fail silently:
            // Jackson's NON_NULL inclusion, Map.of, and a JSONB round trip would each drop it,
            // and the edit would simply never arrive with no error anywhere.
            phone.engine().upsert(EntityType.HABIT, habitId, "colour", FieldValue.NULL);
            phone.engine().sync();

            tablet.engine().sync();

            EntityRecord habit = tablet.engine().load(EntityType.HABIT, habitId).orElseThrow();
            assertThat(habit.fields())
                    .as("a cleared field must arrive as a present key with a null value")
                    .containsKey("colour");
            assertThat(habit.field("colour").isNull()).isTrue();
            assertThat(habit.field("name")).isEqualTo(FieldValue.of("Run"));
        }
    }

    @Test
    @DisplayName("a delete on one device hides the habit on the other, and survives a restart")
    void deletePropagatesAndPersists(@TempDir Path directory) throws TransportException {
        String email = "e2e-delete-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery-staple";
        UUID habitId = UUID.randomUUID();
        Path phoneDb = directory.resolve("phone.db");

        Session sessionA = HttpTransport.register(baseUrl(), email, password, "Phone");
        Session sessionB = HttpTransport.login(baseUrl(), email, password, "Tablet", null);

        try (Device phone = open(phoneDb, sessionA);
                Device tablet = open(directory.resolve("tablet.db"), sessionB)) {

            phone.engine().upsert(EntityType.HABIT, habitId, "name", FieldValue.of("Run"));
            phone.engine().sync();
            tablet.engine().sync();

            tablet.engine().delete(EntityType.HABIT, habitId);
            tablet.engine().sync();
            phone.engine().sync();

            EntityRecord habit = phone.engine().load(EntityType.HABIT, habitId).orElseThrow();
            assertThat(habit.visible()).isFalse();
            // The tombstone hides the entity; it does not erase the field registers. Coupling
            // the two would break merge's commutativity -- see EntityRecord.
            assertThat(habit.field("name")).isEqualTo(FieldValue.of("Run"));
        }

        // Reopen the phone's database as a fresh process would.
        try (Device restarted = open(phoneDb, sessionA)) {
            assertThat(restarted.engine().load(EntityType.HABIT, habitId))
                    .get()
                    .extracting(EntityRecord::visible)
                    .isEqualTo(false);
            assertThat(restarted.store().watermark())
                    .as("the watermark is durable; a device that forgot it would re-pull everything")
                    .isPositive();
        }
    }
}
