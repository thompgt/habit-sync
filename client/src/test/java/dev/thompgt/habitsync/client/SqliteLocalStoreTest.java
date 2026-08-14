package dev.thompgt.habitsync.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thompgt.habitsync.sync.Change;
import dev.thompgt.habitsync.sync.EntityKey;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import dev.thompgt.habitsync.sync.Hlc;
import dev.thompgt.habitsync.sync.MergeEngine;
import dev.thompgt.habitsync.sync.SyncEngine;
import dev.thompgt.habitsync.sync.TimeSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LocalStore}'s contract, checked against the implementation that actually has to
 * honour it on disk.
 *
 * <p>The interesting cases are the ones an in-memory store gets right for free: that a
 * cleared field survives a round trip as a cleared field rather than as an absent one, and
 * that state written before a restart is still there afterwards. Both have natural
 * implementations that fail silently — a NULL that reads back as a missing row looks like a
 * field the user never set.
 */
class SqliteLocalStoreTest {

    private static final MergeEngine MERGE = new MergeEngine();

    private static Hlc hlc(long millis, String node) {
        return new Hlc(millis, 0, node);
    }

    private static EntityRecord merged(EntityRecord current, Change change) {
        return MERGE.merge(current, change).state();
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {

        @Test
        @DisplayName("a cleared field reads back as cleared, not as absent")
        void clearedFieldsSurvive() {
            try (SqliteLocalStore store = SqliteLocalStore.inMemory()) {
                UUID id = UUID.randomUUID();
                EntityKey key = new EntityKey(EntityType.HABIT, id);

                Change set = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(1000, "a"), Map.of("colour", FieldValue.of("red")));
                store.applyLocal(merged(null, set), set);

                Change clear = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(2000, "a"), Map.of("colour", FieldValue.NULL));
                store.applyLocal(merged(store.load(key).orElseThrow(), clear), clear);

                EntityRecord reloaded = store.load(key).orElseThrow();

                // Present key, null value. If this came back as an absent key the field would
                // look like one the user never set, and the next merge would treat any write
                // as unopposed rather than as contending with the clear.
                assertThat(reloaded.fields()).containsKey("colour");
                assertThat(reloaded.field("colour").isNull()).isTrue();
                assertThat(reloaded.clockOf("colour")).isEqualTo(hlc(2000, "a"));
            }
        }

        @Test
        @DisplayName("tombstones and their lifecycle clocks survive")
        void tombstonesSurvive() {
            try (SqliteLocalStore store = SqliteLocalStore.inMemory()) {
                UUID id = UUID.randomUUID();
                EntityKey key = new EntityKey(EntityType.HABIT, id);

                Change create = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(1000, "a"), Map.of("name", FieldValue.of("Run")));
                store.applyLocal(merged(null, create), create);

                Change delete = Change.delete(UUID.randomUUID(), EntityType.HABIT, id, hlc(2000, "a"));
                store.applyLocal(merged(store.load(key).orElseThrow(), delete), delete);

                EntityRecord reloaded = store.load(key).orElseThrow();
                assertThat(reloaded.deleted()).isTrue();
                assertThat(reloaded.lifecycleClock()).isEqualTo(hlc(2000, "a"));
                // The field register is untouched by the tombstone -- the two groups are
                // orthogonal, and coupling them breaks commutativity.
                assertThat(reloaded.field("name")).isEqualTo(FieldValue.of("Run"));
            }
        }
    }

    @Nested
    @DisplayName("durability")
    class Durability {

        @Test
        @DisplayName("state, outbox and clock all survive a reopen")
        void survivesRestart(@TempDir Path directory) {
            Path database = directory.resolve("habits.db");
            UUID id = UUID.randomUUID();

            try (SqliteLocalStore store = new SqliteLocalStore(database.toString())) {
                Change create = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(1000, "a"), Map.of("name", FieldValue.of("Run")));
                store.applyLocal(merged(null, create), create);
                store.applyRemote(List.of(), 42L, hlc(3000, "b"));
            }

            try (SqliteLocalStore reopened = new SqliteLocalStore(database.toString())) {
                assertThat(reopened.load(new EntityKey(EntityType.HABIT, id))).isPresent();
                assertThat(reopened.watermark()).isEqualTo(42L);
                assertThat(reopened.pendingOpCount()).isEqualTo(1);

                // The clock is the one thing that must never regress across a restart. A
                // device that restarts from zero reissues timestamps it has already used, and
                // two writes sharing an HLC is the one way a client can break convergence.
                assertThat(reopened.lastClock()).contains(hlc(3000, "b"));
            }
        }

        @Test
        @DisplayName("a restarted device keeps stamping above what it already used")
        void clockDoesNotRegressAcrossRestart(@TempDir Path directory) {
            Path database = directory.resolve("habits.db");
            String node = "device-a";
            // Frozen, so physical time cannot separate the two writes and only a restored
            // logical counter can. Without restoration the second engine starts from zero and
            // reissues the exact timestamp the first one already used -- which is the one
            // client-side way to break convergence, since merge's strictly-greater rule then
            // lets replicas seeing the two writes in different orders pick different winners.
            TimeSource frozen = () -> 500L;

            Hlc firstWrite;
            try (SqliteLocalStore store = new SqliteLocalStore(database.toString())) {
                SyncEngine engine = SyncEngine.forDevice(node, store, request -> {
                    throw new UnsupportedOperationException();
                }, frozen);
                firstWrite = engine.upsert(
                                EntityType.HABIT, UUID.randomUUID(), "name", FieldValue.of("Run"))
                        .hlc();
            }

            try (SqliteLocalStore reopened = new SqliteLocalStore(database.toString())) {
                SyncEngine engine = SyncEngine.forDevice(node, reopened, request -> {
                    throw new UnsupportedOperationException();
                }, frozen);
                Hlc afterRestart = engine.upsert(EntityType.HABIT, UUID.randomUUID(), "name", FieldValue.of("Jog"))
                        .hlc();

                assertThat(afterRestart.isAfter(firstWrite))
                        .as("a restart must not reissue a timestamp: %s came after %s", afterRestart, firstWrite)
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("the outbox")
    class Outbox {

        @Test
        @DisplayName("returns ops oldest first and clears only what was acknowledged")
        void ordersAndAcknowledges() {
            try (SqliteLocalStore store = SqliteLocalStore.inMemory()) {
                UUID id = UUID.randomUUID();
                Change first = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(1000, "a"), Map.of("name", FieldValue.of("Run")));
                Change second = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(2000, "a"), Map.of("name", FieldValue.of("Jog")));
                store.applyLocal(merged(null, first), first);
                store.applyLocal(merged(store.load(first.key()).orElseThrow(), second), second);

                assertThat(store.pendingOps(10)).extracting(Change::opId)
                        .containsExactly(first.opId(), second.opId());

                store.acknowledgeOps(List.of(first.opId()));

                assertThat(store.pendingOps(10)).extracting(Change::opId).containsExactly(second.opId());
                assertThat(store.pendingOpCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a resync wipes state but keeps the outbox and the clock")
        void resyncKeepsUnpushedWork() {
            try (SqliteLocalStore store = SqliteLocalStore.inMemory()) {
                UUID id = UUID.randomUUID();
                Change create = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(1000, "a"), Map.of("name", FieldValue.of("Run")));
                store.applyLocal(merged(null, create), create);
                store.applyRemote(List.of(), 7L, hlc(1500, "a"));

                store.resetForResync();

                assertThat(store.load(new EntityKey(EntityType.HABIT, id))).isEmpty();
                assertThat(store.watermark()).isZero();
                // The server's retention policy is no reason to bin a week of the user's
                // offline work, and the clock is this device's identity over time.
                assertThat(store.pendingOpCount()).isEqualTo(1);
                assertThat(store.lastClock()).isPresent();
            }
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("the watermark refuses to go backwards")
        void watermarkNeverRegresses() {
            try (SqliteLocalStore store = SqliteLocalStore.inMemory()) {
                store.applyRemote(List.of(), 10L, hlc(1000, "a"));

                assertThatThrownBy(() -> store.applyRemote(List.of(), 9L, hlc(2000, "a")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("backwards");
            }
        }

        @Test
        @DisplayName("an op that does not match its record is rejected")
        void mismatchedApplyLocalIsRejected() {
            try (SqliteLocalStore store = SqliteLocalStore.inMemory()) {
                UUID id = UUID.randomUUID();
                Change change = Change.upsert(
                        UUID.randomUUID(), EntityType.HABIT, id, hlc(1000, "a"), Map.of("name", FieldValue.of("Run")));
                EntityRecord unrelated = EntityRecord.empty(EntityType.HABIT, UUID.randomUUID());

                assertThatThrownBy(() -> store.applyLocal(unrelated, change))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("a fresh store reports nothing")
        void freshStoreIsEmpty() {
            try (SqliteLocalStore store = SqliteLocalStore.inMemory()) {
                assertThat(store.watermark()).isZero();
                assertThat(store.pendingOpCount()).isZero();
                assertThat(store.lastClock()).isEqualTo(Optional.empty());
                assertThat(store.allRecords()).isEmpty();
            }
        }
    }
}
