package dev.thompgt.habitsync.sim;

import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the users of the simulated account actually do: keep habits, tick them off, and log
 * workouts.
 *
 * <p>The generator's job is not realism for its own sake. It is to produce the two shapes of
 * data ADR-001 treats differently — mutable entities whose fields contend, and create-once
 * facts that cannot conflict — and to make replicas contend on the <em>same</em> entities
 * often enough that per-field LWW is genuinely exercised. A workload where each replica edits
 * its own entities converges trivially and proves nothing.
 *
 * <h2>Why the entity pool is shared</h2>
 *
 * Every replica draws from one pool of ids, including ids it has not yet synced. That is
 * harsher than a real device, which would only edit what it had seen, and it is deliberate:
 * an UPSERT on an unknown id creates the entity locally, so two replicas writing different
 * fields of an id neither has received is precisely the concurrent-create case that per-row
 * LWW silently resolves by throwing one side away. It is also a reachable real state — the id
 * arrived on one device, was synced to a second, and the first was rolled back — just a rare
 * one.
 *
 * <h2>Clearing fields</h2>
 *
 * A slice of the writes set a field to {@link FieldValue#NULL} rather than to a value.
 * "Cleared" and "never written" are different states throughout the protocol, and the
 * distinction is fragile in exactly the places a generator that only ever wrote non-null
 * values would never visit — JSON serialisation, map copying, and the bootstrap snapshot all
 * have a natural implementation that quietly drops nulls.
 */
public final class Workload {

    private static final List<String> HABIT_NAMES =
            List.of("Run", "Read", "Meditate", "Stretch", "Journal", "Swim", "Cycle");
    private static final List<String> COLOURS = List.of("red", "green", "blue", "amber", "violet");
    private static final List<String> EXERCISES =
            List.of("Squat", "Bench", "Deadlift", "Row", "Press", "Pull-up");
    private static final List<String> MUSCLE_GROUPS = List.of("legs", "chest", "back", "shoulders");

    private final SimRandom random;

    /** Ids by type, shared across replicas so their writes contend. */
    private final Map<EntityType, List<UUID>> pool = new LinkedHashMap<>();

    private int writeCounter;

    public Workload(SimRandom random) {
        this.random = random;
        for (EntityType type : EntityType.values()) {
            pool.put(type, new ArrayList<>());
        }
    }

    /**
     * Performs one user action on {@code replica} and returns a description of it for the
     * run's history.
     *
     * <p>The description is not decoration. When a run diverges, the history is what turns
     * "seed 8412 fails" into a sequence a human can read, and it is the difference between a
     * simulator that finds bugs and one that only reports their existence.
     */
    public String act(Replica replica) {
        replica.countEdit();
        int roll = random.nextInt(100);

        if (roll < 20 || pool.get(EntityType.HABIT).isEmpty()) {
            return createHabit(replica);
        }
        if (roll < 45) {
            return editHabit(replica);
        }
        if (roll < 55) {
            return deleteOrRestore(replica, EntityType.HABIT);
        }
        if (roll < 75) {
            return logCompletion(replica);
        }
        if (roll < 85) {
            return createExercise(replica);
        }
        return logWorkoutSet(replica);
    }

    // ------------------------------------------------------- mutable entities

    private String createHabit(Replica replica) {
        UUID id = newId(EntityType.HABIT);
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        fields.put("name", FieldValue.of(random.pick(HABIT_NAMES) + " " + (++writeCounter)));
        fields.put("weeklyTarget", FieldValue.of(random.nextInt(1, 8)));
        fields.put("colour", FieldValue.of(random.pick(COLOURS)));
        replica.engine().upsert(EntityType.HABIT, id, fields);
        return "%s creates HABIT %s %s".formatted(replica.nodeId(), shortId(id), fields);
    }

    /**
     * Writes a single field of an existing habit.
     *
     * <p>One field at a time on purpose: two replicas editing different fields of the same
     * habit is the case per-field LWW exists for, and it only arises if writes are narrow. A
     * generator that always wrote the whole entity would make per-field and per-row LWW
     * indistinguishable, and the simulator would pass against an implementation of the wrong
     * one.
     */
    private String editHabit(Replica replica) {
        UUID id = random.pick(pool.get(EntityType.HABIT));
        int which = random.nextInt(4);
        String field;
        FieldValue value;
        switch (which) {
            case 0 -> {
                field = "name";
                value = FieldValue.of(random.pick(HABIT_NAMES) + " " + (++writeCounter));
            }
            case 1 -> {
                field = "weeklyTarget";
                value = FieldValue.of(random.nextInt(1, 8));
            }
            case 2 -> {
                field = "colour";
                value = FieldValue.of(random.pick(COLOURS));
            }
            default -> {
                field = "colour";
                // An explicitly cleared field, which is not the same as an absent one.
                value = FieldValue.NULL;
            }
        }
        replica.engine().upsert(EntityType.HABIT, id, field, value);
        return "%s sets %s.%s = %s on %s".formatted(replica.nodeId(), "HABIT", field, value, shortId(id));
    }

    private String deleteOrRestore(Replica replica, EntityType type) {
        UUID id = random.pick(pool.get(type));
        boolean restore = replica
                .engine()
                .load(type, id)
                // Restoring is the user's explicit undo, so it only follows a delete this
                // replica can actually see. Emitting RESTOREs at random would test a
                // transition the application never produces.
                .map(record -> record.deleted())
                .orElse(false);

        if (restore) {
            replica.engine().restore(type, id);
            return "%s restores %s %s".formatted(replica.nodeId(), type, shortId(id));
        }
        replica.engine().delete(type, id);
        return "%s deletes %s %s".formatted(replica.nodeId(), type, shortId(id));
    }

    private String createExercise(Replica replica) {
        UUID id = newId(EntityType.EXERCISE);
        replica.engine()
                .upsert(
                        EntityType.EXERCISE,
                        id,
                        Map.of(
                                "name", FieldValue.of(random.pick(EXERCISES)),
                                "muscleGroup", FieldValue.of(random.pick(MUSCLE_GROUPS))));
        return "%s creates EXERCISE %s".formatted(replica.nodeId(), shortId(id));
    }

    // ---------------------------------------------------- create-once facts

    /**
     * An append-only fact: written once and never edited.
     *
     * <p>These genuinely cannot conflict, which is the claim worth testing rather than
     * assuming. They still travel the whole protocol — log, page, bootstrap, retention — so a
     * bug in any of that shows up here as a missing completion on one replica.
     */
    private String logCompletion(Replica replica) {
        UUID id = newId(EntityType.HABIT_COMPLETION);
        UUID habitId = random.pick(pool.get(EntityType.HABIT));
        replica.engine()
                .upsert(
                        EntityType.HABIT_COMPLETION,
                        id,
                        Map.of(
                                "habitId", FieldValue.of(habitId.toString()),
                                "at", FieldValue.of(replica.clock().currentTimeMillis())));
        return "%s completes HABIT %s".formatted(replica.nodeId(), shortId(habitId));
    }

    private String logWorkoutSet(Replica replica) {
        List<UUID> sessions = pool.get(EntityType.WORKOUT_SESSION);
        UUID sessionId;
        if (sessions.isEmpty() || random.chance(0.3)) {
            sessionId = newId(EntityType.WORKOUT_SESSION);
            replica.engine()
                    .upsert(
                            EntityType.WORKOUT_SESSION,
                            sessionId,
                            "startedAt",
                            FieldValue.of(replica.clock().currentTimeMillis()));
        } else {
            sessionId = random.pick(sessions);
        }

        List<UUID> exercises = pool.get(EntityType.EXERCISE);
        if (exercises.isEmpty()) {
            return createExercise(replica);
        }

        UUID setId = newId(EntityType.WORKOUT_SET);
        replica.engine()
                .upsert(
                        EntityType.WORKOUT_SET,
                        setId,
                        Map.of(
                                "sessionId", FieldValue.of(sessionId.toString()),
                                "exerciseId", FieldValue.of(random.pick(exercises).toString()),
                                "reps", FieldValue.of(random.nextInt(1, 16)),
                                "weightKg", FieldValue.of(random.nextInt(20, 181))));
        return "%s logs WORKOUT_SET %s in session %s"
                .formatted(replica.nodeId(), shortId(setId), shortId(sessionId));
    }

    // ------------------------------------------------------------- plumbing

    private UUID newId(EntityType type) {
        UUID id = random.nextUuid();
        pool.get(type).add(id);
        return id;
    }

    /** Eight characters is plenty to follow one entity through a history by eye. */
    static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
