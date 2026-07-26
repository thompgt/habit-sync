package dev.thompgt.habitsync.sync;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link Change} in its over-the-wire form: enums as strings, the HLC in its compact
 * encoding, field values as plain text.
 *
 * <p>Deliberately not the same type as {@code Change}. The wire format is a compatibility
 * surface that outlives any single client version, and keeping it separate means an
 * unknown enum value from a newer client is a validation error the server can answer with
 * a 400, rather than a deserialisation crash. It also means {@code Change} stays free to
 * gain type-safe structure without breaking every deployed device.
 *
 * <p>This record lives in sync-core, not in the server, so that both ends of the protocol
 * encode against one definition. When the server owned its own copy, the two could drift
 * in exactly the way that is hardest to notice — a field one side writes and the other
 * silently ignores, discovered as missing data weeks later.
 *
 * <h2>Null field values are load-bearing</h2>
 *
 * {@code fields} permits null <em>values</em>, and the map is therefore not built with
 * {@link Map#copyOf}, which rejects them. A present key with a null value means "clear
 * this field"; an absent key means "this change does not touch it". Conflating the two
 * turns every clear operation into a silent no-op — see {@link FieldValue}.
 *
 * @param opId       client-generated, stable across retries; the basis of push idempotency
 * @param entityType {@link EntityType} name
 * @param entityId   client-generated entity id
 * @param kind       {@link OpKind} name
 * @param hlc        {@link Hlc#toCompactString()} form
 * @param fields     field writes for {@code UPSERT}; empty otherwise
 */
public record WireChange(
        UUID opId,
        String entityType,
        UUID entityId,
        String kind,
        String hlc,
        Map<String, String> fields) {

    public WireChange {
        // No null checks and no Map.copyOf here: this type is populated by a JSON parser
        // from untrusted input, and a half-built instance must be constructible so that
        // ChangeCodec can report *which* part is wrong. Validation belongs in decode.
        fields = fields == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** The change this describes, for logs and error messages. */
    @Override
    public String toString() {
        return "%s %s/%s @%s".formatted(kind, entityType, entityId, hlc);
    }

}
