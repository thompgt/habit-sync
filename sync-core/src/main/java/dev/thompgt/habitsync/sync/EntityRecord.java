package dev.thompgt.habitsync.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The merged state of one entity: its field values, the clock reading that last wrote
 * each field, and its lifecycle (deleted or not).
 *
 * <p><b>The two register groups are orthogonal, and that is not an accident.</b> Field
 * writes are applied regardless of whether the entity is deleted, and the tombstone is
 * applied regardless of what the fields say. Deletion affects <em>visibility at read
 * time</em> — see {@link #visible()} — not the field registers.
 *
 * <p>Coupling them breaks convergence. Suppose {@code UPSERT(name=X, hlc=5)} and
 * {@code DELETE(hlc=3)} are merged in different orders by two replicas, and suppose the
 * engine discarded field writes on tombstoned entities:
 *
 * <pre>
 *   Replica A:  UPSERT then DELETE  ->  deleted, name = X
 *   Replica B:  DELETE then UPSERT  ->  deleted, name = &lt;unset&gt;   // discarded
 * </pre>
 *
 * The replicas now disagree. Keeping the groups independent makes merge commutative, so
 * both land on {@code deleted, name = X} and simply do not show it to the user.
 *
 * <p>Instances are immutable; every merge produces a new record.
 *
 * @param type          entity type
 * @param id            entity id
 * @param fields        current field values
 * @param fieldClocks   HLC that last wrote each field; keys always match {@code fields}
 * @param deleted       whether a tombstone is currently in force
 * @param lifecycleClock HLC of the last {@link OpKind#DELETE} or {@link OpKind#RESTORE},
 *                      or {@code null} if the entity has never been either
 */
public record EntityRecord(
        EntityType type,
        UUID id,
        Map<String, FieldValue> fields,
        Map<String, Hlc> fieldClocks,
        boolean deleted,
        Hlc lifecycleClock) {

    public EntityRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
        fieldClocks = Map.copyOf(Objects.requireNonNull(fieldClocks, "fieldClocks"));

        if (!fields.keySet().equals(fieldClocks.keySet())) {
            throw new IllegalArgumentException(
                    "Every field must carry exactly one clock; fields=%s clocks=%s"
                            .formatted(fields.keySet(), fieldClocks.keySet()));
        }
        if (deleted && lifecycleClock == null) {
            throw new IllegalArgumentException("A deleted record must carry a lifecycleClock");
        }
    }

    /** An entity with no fields written and no tombstone. */
    public static EntityRecord empty(EntityType type, UUID id) {
        return new EntityRecord(type, id, Map.of(), Map.of(), false, null);
    }

    public static EntityRecord empty(EntityKey key) {
        return empty(key.type(), key.id());
    }

    public EntityKey key() {
        return new EntityKey(type, id);
    }

    /**
     * Whether the application should show this entity.
     *
     * <p>The only place deletion is allowed to influence behaviour. Queries filter on
     * this; merge never does.
     */
    public boolean visible() {
        return !deleted;
    }

    /** @return the value of {@code field}, or {@code null} if it has never been written. */
    public FieldValue field(String field) {
        return fields.get(field);
    }

    /** @return the clock that last wrote {@code field}, or {@code null} if never written. */
    public Hlc clockOf(String field) {
        return fieldClocks.get(field);
    }

    /** Returns a copy with {@code field} set to {@code value} as of {@code clock}. */
    EntityRecord withField(String field, FieldValue value, Hlc clock) {
        Map<String, FieldValue> nextFields = new LinkedHashMap<>(fields);
        Map<String, Hlc> nextClocks = new LinkedHashMap<>(fieldClocks);
        nextFields.put(field, value);
        nextClocks.put(field, clock);
        return new EntityRecord(type, id, nextFields, nextClocks, deleted, lifecycleClock);
    }

    /** Returns a copy with the lifecycle register set. */
    EntityRecord withLifecycle(boolean nowDeleted, Hlc clock) {
        return new EntityRecord(type, id, fields, fieldClocks, nowDeleted, clock);
    }
}
