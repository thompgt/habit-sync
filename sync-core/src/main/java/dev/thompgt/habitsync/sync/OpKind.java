package dev.thompgt.habitsync.sync;

/**
 * What a {@link Change} does to an entity.
 *
 * <p>There is deliberately no {@code CREATE}. Entity ids are generated client-side as
 * UUIDs, so "create" and "update" are the same operation — an {@link #UPSERT} that
 * happens to be the first one seen. Collapsing them removes an entire class of conflict
 * (create-vs-update ordering) rather than resolving it.
 */
public enum OpKind {
    /** Write one or more field values. Creates the entity if it is not yet known. */
    UPSERT,

    /** Tombstone the entity. See ADR-003. */
    DELETE,

    /**
     * Clear a tombstone. Only ever produced by an explicit user "undo"; deletion is
     * never undone as a side effect of clock ordering.
     */
    RESTORE
}
