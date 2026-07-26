package dev.thompgt.habitsync.sync;

/**
 * The kinds of entity the sync engine replicates.
 *
 * <p>Note there is no "append-only" flag here, and no special code path for
 * {@link #HABIT_COMPLETION} or {@link #WORKOUT_SET}. Append-only-ness is a property of
 * how the application <em>uses</em> those entities — it creates them once and never
 * edits them — not a rule the merge engine enforces. Uniform treatment means one merge
 * path to reason about and test, and it leaves the door open to editing a mis-logged
 * set later without touching the engine.
 */
public enum EntityType {
    /** A habit definition: name, weekly target, colour. Mutable. */
    HABIT,

    /** An exercise definition: name, muscle group. Mutable. */
    EXERCISE,

    /** A record that a habit was completed at a point in time. Created once. */
    HABIT_COMPLETION,

    /** A workout session envelope: start and end times. */
    WORKOUT_SESSION,

    /** One set within a session: exercise, reps, weight. Created once. */
    WORKOUT_SET
}
