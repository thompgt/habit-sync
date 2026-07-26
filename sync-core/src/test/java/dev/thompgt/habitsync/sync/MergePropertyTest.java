package dev.thompgt.habitsync.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * The algebraic properties that make the sync layer correct. Everything else in this
 * project is scaffolding around these three assertions.
 *
 * <p>The network delivers changes out of order, more than once, and split across
 * retries. Merge must therefore be commutative, idempotent, and associative — if it is,
 * replicas converge no matter what the network does to them; if it isn't, no amount of
 * careful protocol design can save it.
 *
 * <h2>A generator invariant worth understanding</h2>
 *
 * These generators assign every change a <b>unique HLC</b> (the list index becomes the
 * logical counter). That mirrors a real system invariant rather than dodging a hard
 * case: a node's {@link HlcClock#tick()} is strictly monotonic, so one device can never
 * stamp two different writes with the same HLC, and the {@code nodeId} component means
 * two devices never collide either.
 *
 * <p>Generating duplicate HLCs carrying <em>different</em> values would violate that
 * invariant and merge genuinely would not commute — first writer would win. This is
 * exactly why {@link Hlc}'s ordering must be total and consistent with equals.
 */
class MergePropertyTest {

    private static final int MAX_ENTITIES = 3;
    private static final List<String> FIELDS = List.of("name", "targetPerWeek", "colour");
    private static final List<String> NODES = List.of("device-a", "device-b", "device-c");

    private final MergeEngine engine = new MergeEngine();

    // ---------------------------------------------------------------- properties

    @Property(tries = 2_000)
    void mergeIsCommutativeForAnyPairOfChanges(
            @ForAll @IntRange(min = 0, max = 4096) int seedA,
            @ForAll @IntRange(min = 0, max = 4096) int seedB) {

        // Same entity, distinct HLCs — the case where a conflict is actually possible.
        Change a = change(seedA, 0, 0);
        Change b = change(seedB, 0, 1);

        EntityRecord ab = engine.merge(engine.merge(null, a).state(), b).state();
        EntityRecord ba = engine.merge(engine.merge(null, b).state(), a).state();

        assertThat(ab)
                .as("merge(%s, merge(%s, empty)) must equal merge(%s, merge(%s, empty))", b, a, a, b)
                .isEqualTo(ba);
    }

    @Property(tries = 2_000)
    void mergeIsIdempotent(@ForAll @IntRange(min = 0, max = 4096) int seed) {
        Change change = change(seed, 0, 0);

        EntityRecord once = engine.merge(null, change).state();
        EntityRecord twice = engine.merge(once, change).state();

        assertThat(twice).as("re-delivering a change must not alter state").isEqualTo(once);
    }

    /**
     * The headline property: any two replicas fed the same set of changes in any order,
     * with arbitrary duplication, reach identical state.
     *
     * <p>This is a miniature of what the M6 simulator does across a real transport.
     */
    @Property(tries = 1_000)
    void replicasConvergeUnderArbitraryOrderingAndDuplication(
            @ForAll @Size(min = 1, max = 25) List<@IntRange(min = 0, max = 4096) Integer> seeds,
            @ForAll long shuffleSeed) {

        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            changes.add(change(seeds.get(i), i % MAX_ENTITIES, i));
        }

        Random random = new Random(shuffleSeed);

        // Replica 1: the changes in generated order.
        Map<EntityKey, EntityRecord> replica1 = applyAll(changes);

        // Replica 2: shuffled, with a random subset delivered twice.
        List<Change> shuffled = new ArrayList<>(changes);
        Collections.shuffle(shuffled, random);
        for (Change change : changes) {
            if (random.nextBoolean()) {
                shuffled.add(random.nextInt(shuffled.size() + 1), change);
            }
        }
        Map<EntityKey, EntityRecord> replica2 = applyAll(shuffled);

        // Replica 3: reverse order, every change delivered three times.
        List<Change> tripled = new ArrayList<>();
        for (Change change : changes) {
            tripled.add(change);
            tripled.add(change);
            tripled.add(change);
        }
        Collections.reverse(tripled);
        Map<EntityKey, EntityRecord> replica3 = applyAll(tripled);

        assertThat(replica2).as("shuffled + duplicated delivery must converge").isEqualTo(replica1);
        assertThat(replica3).as("reversed + tripled delivery must converge").isEqualTo(replica1);
    }

    /** Associativity: how changes are grouped into pages must not matter. */
    @Property(tries = 1_000)
    void mergeIsAssociativeAcrossBatchBoundaries(
            @ForAll @Size(min = 2, max = 20) List<@IntRange(min = 0, max = 4096) Integer> seeds,
            @ForAll @IntRange(min = 1, max = 19) int splitPoint) {

        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            changes.add(change(seeds.get(i), 0, i));
        }
        int split = Math.min(splitPoint, changes.size() - 1);

        EntityRecord wholeBatch = engine.mergeAll(null, changes);
        EntityRecord twoBatches = engine.mergeAll(
                engine.mergeAll(null, changes.subList(0, split)), changes.subList(split, changes.size()));

        assertThat(twoBatches).isEqualTo(wholeBatch);
    }

    /**
     * A deleted entity stays invisible no matter what arrives afterwards, unless an
     * explicit RESTORE with a strictly greater HLC does so. Deletes must not be undone
     * by clock ordering alone — see ADR-003.
     */
    @Property(tries = 1_000)
    void onlyAnExplicitLaterRestoreCanMakeADeletedEntityVisible(
            @ForAll @Size(min = 1, max = 15) List<@IntRange(min = 0, max = 4096) Integer> seeds) {

        UUID entityId = entityId(0);
        Hlc deleteAt = new Hlc(1_000_000, 0, "device-a");
        EntityRecord state = engine
                .merge(null, Change.delete(UUID.randomUUID(), EntityType.HABIT, entityId, deleteAt))
                .state();

        // Feed it only UPSERTs, all stamped later than the delete.
        for (int i = 0; i < seeds.size(); i++) {
            Hlc later = new Hlc(2_000_000 + i, 0, NODES.get(seeds.get(i) % NODES.size()));
            state = engine
                    .merge(
                            state,
                            Change.upsert(
                                    UUID.randomUUID(),
                                    EntityType.HABIT,
                                    entityId,
                                    later,
                                    Map.of(FIELDS.get(seeds.get(i) % FIELDS.size()), FieldValue.of("v" + i))))
                    .state();
        }

        assertThat(state.visible()).as("field edits must never resurrect a deleted entity").isFalse();
    }

    // ---------------------------------------------------------------- generators

    private Map<EntityKey, EntityRecord> applyAll(List<Change> changes) {
        Map<EntityKey, EntityRecord> byKey = new java.util.HashMap<>();
        for (Change change : changes) {
            byKey.merge(
                    change.key(),
                    engine.merge(null, change).state(),
                    (existing, ignored) -> engine.merge(existing, change).state());
        }
        return byKey;
    }

    /**
     * Derives a deterministic change from an integer seed.
     *
     * <p>The clock assignment is chosen so that consecutive indices land on the <b>same
     * {@code (physical, logical)} pair but different nodes</b>. That deliberately
     * manufactures the exact-tie case — two devices stamping the same millisecond — which
     * only the {@code nodeId} tiebreak can resolve. An earlier version of this generator
     * gave every change a unique logical counter, which meant ties never arose and the
     * properties happily passed with the tiebreak deleted. Ties must be generated, not
     * hoped for.
     *
     * <p>Global HLC uniqueness still holds: for a fixed node, {@code k = index / 3} is
     * distinct, and {@code k -> (k % 4, k / 4)} is injective, so no two changes from one
     * node share a timestamp.
     *
     * @param seed          drives kind, field, and value
     * @param entityIndex   which of the entities this targets
     * @param uniqueCounter position in the batch; drives the clock assignment above
     */
    private static Change change(int seed, int entityIndex, int uniqueCounter) {
        UUID entityId = entityId(entityIndex);
        String node = NODES.get(Math.floorMod(uniqueCounter, NODES.size()));
        int k = Math.floorDiv(uniqueCounter, NODES.size());
        Hlc hlc = new Hlc(1_000 + (k / 4), k % 4, node);
        UUID opId = UUID.nameUUIDFromBytes(("op-" + seed + "-" + uniqueCounter).getBytes());

        int kindSelector = Math.floorMod(seed / 7, 10);
        if (kindSelector == 0) {
            return Change.delete(opId, EntityType.HABIT, entityId, hlc);
        }
        if (kindSelector == 1) {
            return Change.restore(opId, EntityType.HABIT, entityId, hlc);
        }

        String field = FIELDS.get(Math.floorMod(seed / 3, FIELDS.size()));
        FieldValue value = Math.floorMod(seed, 11) == 0
                ? FieldValue.NULL
                : FieldValue.of("value-" + Math.floorMod(seed, 17));
        return Change.upsert(opId, EntityType.HABIT, entityId, hlc, Map.of(field, value));
    }

    private static UUID entityId(int index) {
        return UUID.nameUUIDFromBytes(("entity-" + Math.floorMod(index, MAX_ENTITIES)).getBytes());
    }
}
