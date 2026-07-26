package dev.thompgt.habitsync.sync;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies an entity across types. Entity ids are UUIDs and so are globally unique on
 * their own, but pairing them with the type keeps lookups typed and makes a
 * wrong-table bug a compile-time or assertion failure rather than a silent miss.
 */
public record EntityKey(EntityType type, UUID id) {

    public EntityKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    @Override
    public String toString() {
        return type + "/" + id;
    }
}
