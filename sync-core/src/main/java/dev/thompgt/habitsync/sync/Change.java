package dev.thompgt.habitsync.sync;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One mutation to one entity — the atom that flows over the wire and through
 * {@link MergeEngine}.
 *
 * <p>A change is immutable and self-describing: everything needed to merge it is
 * present, so it can be applied on any replica, in any order, any number of times.
 *
 * @param opId       client-generated identifier, stable across retries. This is what
 *                   makes push idempotent: a request that times out after the server
 *                   committed is safe to replay, because the server recognises the
 *                   {@code opId} and treats the replay as a no-op instead of
 *                   double-applying it.
 * @param entityType which table this targets
 * @param entityId   client-generated entity id (UUIDv7 in practice, so ids sort roughly
 *                   by creation time and index locality stays reasonable)
 * @param kind       what the change does
 * @param hlc        the originating device's clock reading, and the sole basis for
 *                   conflict resolution
 * @param fields     field writes for {@link OpKind#UPSERT}; always empty for
 *                   {@link OpKind#DELETE} and {@link OpKind#RESTORE}
 */
public record Change(
        UUID opId,
        EntityType entityType,
        UUID entityId,
        OpKind kind,
        Hlc hlc,
        Map<String, FieldValue> fields) {

    public Change {
        Objects.requireNonNull(opId, "opId");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(hlc, "hlc");
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));

        if (kind != OpKind.UPSERT && !fields.isEmpty()) {
            throw new IllegalArgumentException(
                    kind + " changes must not carry field writes, got: " + fields.keySet());
        }
    }

    public static Change upsert(
            UUID opId, EntityType type, UUID entityId, Hlc hlc, Map<String, FieldValue> fields) {
        return new Change(opId, type, entityId, OpKind.UPSERT, hlc, fields);
    }

    public static Change delete(UUID opId, EntityType type, UUID entityId, Hlc hlc) {
        return new Change(opId, type, entityId, OpKind.DELETE, hlc, Map.of());
    }

    public static Change restore(UUID opId, EntityType type, UUID entityId, Hlc hlc) {
        return new Change(opId, type, entityId, OpKind.RESTORE, hlc, Map.of());
    }

    /** Identifies the entity this change targets, for grouping and lookup. */
    public EntityKey key() {
        return new EntityKey(entityType, entityId);
    }
}
