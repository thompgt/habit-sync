package dev.thompgt.habitsync.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Scenario coverage for the conflict table in ADR-001 and the delete semantics in
 * ADR-003. Each nested class corresponds to a row a user could actually hit.
 */
class MergeEngineTest {

    private static final UUID HABIT_ID = UUID.fromString("0192f8a0-0000-7000-8000-000000000001");
    private static final EntityKey KEY = new EntityKey(EntityType.HABIT, HABIT_ID);

    private final MergeEngine engine = new MergeEngine();

    private static Hlc hlc(long physical, String node) {
        return new Hlc(physical, 0, node);
    }

    private static Change upsert(Hlc at, String field, String value) {
        return Change.upsert(
                UUID.randomUUID(), EntityType.HABIT, HABIT_ID, at, Map.of(field, FieldValue.of(value)));
    }

    private static Change upsert(Hlc at, Map<String, FieldValue> fields) {
        return Change.upsert(UUID.randomUUID(), EntityType.HABIT, HABIT_ID, at, fields);
    }

    private static Change delete(Hlc at) {
        return Change.delete(UUID.randomUUID(), EntityType.HABIT, HABIT_ID, at);
    }

    private static Change restore(Hlc at) {
        return Change.restore(UUID.randomUUID(), EntityType.HABIT, HABIT_ID, at);
    }

    @Nested
    @DisplayName("creating and updating")
    class Upserts {

        @Test
        void createsAnEntityFromNothing() {
            MergeResult result = engine.merge(null, upsert(hlc(100, "a"), "name", "Run"));

            assertThat(result.state().key()).isEqualTo(KEY);
            assertThat(result.state().field("name")).isEqualTo(FieldValue.of("Run"));
            assertThat(result.state().visible()).isTrue();
            assertThat(result.mutated()).isTrue();
        }

        @Test
        void newerWriteWinsOnTheSameField() {
            EntityRecord state = engine.merge(null, upsert(hlc(100, "a"), "name", "Run")).state();
            MergeResult result = engine.merge(state, upsert(hlc(200, "b"), "name", "Jog"));

            assertThat(result.state().field("name")).isEqualTo(FieldValue.of("Jog"));
            assertThat(result.superseded()).isEmpty();
        }

        @Test
        void olderWriteLosesAndIsReported() {
            EntityRecord state = engine.merge(null, upsert(hlc(200, "b"), "name", "Jog")).state();
            MergeResult result = engine.merge(state, upsert(hlc(100, "a"), "name", "Run"));

            assertThat(result.state().field("name")).isEqualTo(FieldValue.of("Jog"));
            assertThat(result.mutated()).isFalse();
            assertThat(result.superseded()).singleElement().satisfies(r -> {
                assertThat(r.field()).isEqualTo("name");
                assertThat(r.incoming()).isEqualTo(hlc(100, "a"));
                assertThat(r.existing()).isEqualTo(hlc(200, "b"));
            });
        }

        @Test
        @DisplayName("concurrent edits to DIFFERENT fields both survive — the point of per-field LWW")
        void concurrentEditsToDifferentFieldsBothSurvive() {
            // Device A renames the habit; device B changes its weekly target. Neither
            // saw the other. Per-ROW LWW would silently discard one of these.
            Change renameOnA = upsert(hlc(100, "device-a"), "name", "Morning Run");
            Change retargetOnB = upsert(hlc(101, "device-b"), "targetPerWeek", "5");

            EntityRecord viaA = engine.merge(engine.merge(null, renameOnA).state(), retargetOnB).state();
            EntityRecord viaB = engine.merge(engine.merge(null, retargetOnB).state(), renameOnA).state();

            assertThat(viaA.field("name")).isEqualTo(FieldValue.of("Morning Run"));
            assertThat(viaA.field("targetPerWeek")).isEqualTo(FieldValue.of("5"));
            assertThat(viaB).isEqualTo(viaA);
        }

        @Test
        @DisplayName("exact HLC tie on one field resolves the same way on every replica")
        void tiesResolveDeterministicallyByNodeId() {
            Change fromA = upsert(new Hlc(100, 0, "device-a"), "name", "From A");
            Change fromB = upsert(new Hlc(100, 0, "device-b"), "name", "From B");

            EntityRecord viaA = engine.merge(engine.merge(null, fromA).state(), fromB).state();
            EntityRecord viaB = engine.merge(engine.merge(null, fromB).state(), fromA).state();

            assertThat(viaA).isEqualTo(viaB);
            assertThat(viaA.field("name")).isEqualTo(FieldValue.of("From B")); // "device-b" > "device-a"
        }

        @Test
        void clearingAFieldIsDistinctFromNotTouchingIt() {
            EntityRecord state = engine.merge(
                            null, upsert(hlc(100, "a"), Map.of("colour", FieldValue.of("red"), "name", FieldValue.of("Run"))))
                    .state();

            EntityRecord cleared = engine.merge(
                            state, upsert(hlc(200, "a"), Map.of("colour", FieldValue.NULL)))
                    .state();

            assertThat(cleared.fields()).containsKey("colour");
            assertThat(cleared.field("colour").isNull()).isTrue();
            assertThat(cleared.field("name")).isEqualTo(FieldValue.of("Run")); // untouched
        }

        @Test
        void rejectsAChangeForADifferentEntity() {
            EntityRecord state = EntityRecord.empty(EntityType.HABIT, HABIT_ID);
            Change other = Change.delete(UUID.randomUUID(), EntityType.HABIT, UUID.randomUUID(), hlc(1, "a"));

            assertThatThrownBy(() -> engine.merge(state, other))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("targets");
        }
    }

    @Nested
    @DisplayName("deletion (ADR-003)")
    class Deletion {

        @Test
        void deleteHidesTheEntity() {
            EntityRecord state = engine.merge(null, upsert(hlc(100, "a"), "name", "Run")).state();
            EntityRecord deleted = engine.merge(state, delete(hlc(200, "a"))).state();

            assertThat(deleted.visible()).isFalse();
            assertThat(deleted.deleted()).isTrue();
        }

        @Test
        @DisplayName("a later field edit does NOT resurrect a deleted entity")
        void fieldEditDoesNotResurrect() {
            EntityRecord deleted = engine.merge(null, delete(hlc(100, "a"))).state();
            EntityRecord afterEdit = engine.merge(deleted, upsert(hlc(500, "b"), "name", "Jog")).state();

            assertThat(afterEdit.visible()).isFalse();
            // The field register still advanced — see the orthogonality note below.
            assertThat(afterEdit.field("name")).isEqualTo(FieldValue.of("Jog"));
        }

        @Test
        @DisplayName("delete and edit commute — field registers are orthogonal to the tombstone")
        void deleteAndEditCommute() {
            // This is the case that breaks if the engine discards field writes on
            // tombstoned entities: the two orders would disagree about `name`.
            Change edit = upsert(hlc(500, "b"), "name", "Jog");
            Change del = delete(hlc(100, "a"));

            EntityRecord editFirst = engine.merge(engine.merge(null, edit).state(), del).state();
            EntityRecord deleteFirst = engine.merge(engine.merge(null, del).state(), edit).state();

            assertThat(editFirst).isEqualTo(deleteFirst);
            assertThat(editFirst.visible()).isFalse();
        }

        @Test
        void anOlderDeleteDoesNotOverrideANewerRestore() {
            EntityRecord restored = engine.merge(
                            engine.merge(null, delete(hlc(100, "a"))).state(), restore(hlc(300, "a")))
                    .state();

            EntityRecord afterStaleDelete = engine.merge(restored, delete(hlc(200, "a"))).state();

            assertThat(afterStaleDelete.visible()).isTrue();
        }

        @Test
        void restoreOnlyWinsWhenItIsNewerThanTheDelete() {
            EntityRecord deleted = engine.merge(null, delete(hlc(300, "a"))).state();
            EntityRecord staleRestore = engine.merge(deleted, restore(hlc(100, "a"))).state();

            assertThat(staleRestore.visible()).isFalse();
        }

        @Test
        void deleteAndRestoreCommute() {
            Change del = delete(hlc(300, "a"));
            Change res = restore(hlc(100, "a"));

            EntityRecord order1 = engine.merge(engine.merge(null, del).state(), res).state();
            EntityRecord order2 = engine.merge(engine.merge(null, res).state(), del).state();

            assertThat(order1).isEqualTo(order2);
            assertThat(order1.visible()).isFalse();
        }

        @Test
        void deletingAnEntityNeverSeenBeforeStillTombstonesIt() {
            // Ordinary when a delete arrives before the creation that it deletes.
            EntityRecord state = engine.merge(null, delete(hlc(100, "a"))).state();

            assertThat(state.visible()).isFalse();
            assertThat(state.fields()).isEmpty();
        }
    }

    @Nested
    @DisplayName("replay safety")
    class Idempotence {

        @Test
        void applyingTheSameChangeTwiceIsANoOp() {
            Change change = upsert(hlc(100, "a"), "name", "Run");

            EntityRecord once = engine.merge(null, change).state();
            MergeResult twice = engine.merge(once, change);

            assertThat(twice.state()).isEqualTo(once);
            assertThat(twice.mutated()).isFalse();
        }

        @Test
        void mergeAllIsOrderInsensitiveForAWholeBatch() {
            List<Change> batch = List.of(
                    upsert(hlc(100, "a"), "name", "Run"),
                    upsert(hlc(200, "b"), "targetPerWeek", "4"),
                    delete(hlc(150, "a")),
                    upsert(hlc(300, "a"), "name", "Jog"),
                    restore(hlc(400, "b")));

            List<Change> reversed = new java.util.ArrayList<>(batch);
            java.util.Collections.reverse(reversed);

            EntityRecord forwards = engine.mergeAll(null, batch);
            EntityRecord backwards = engine.mergeAll(null, reversed);

            assertThat(forwards).isEqualTo(backwards);
            assertThat(forwards.field("name")).isEqualTo(FieldValue.of("Jog"));
            assertThat(forwards.visible()).isTrue();
        }
    }
}
