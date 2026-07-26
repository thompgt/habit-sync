package dev.thompgt.habitsync.sync;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates between {@link Change} and {@link WireChange} — the one place the protocol's
 * encoding is defined.
 *
 * <p>Both ends call this. The server decodes what devices push and the client encodes what
 * it sends, so a change that survives one direction survives the other by construction.
 * When each side owned its own translation there was nothing stopping them disagreeing
 * about, say, whether an absent {@code fields} map meant "empty" or "invalid", and that
 * class of bug shows up as data quietly not arriving.
 *
 * <p>Every rejection is an {@link IllegalArgumentException} naming the offending value.
 * That is the right shape for both callers: the server maps it to a 400, because a
 * malformed op describes a defect in the request and a client that receives a 500 will
 * retry the same bad payload forever.
 */
public final class ChangeCodec {

    private ChangeCodec() {}

    /** Encodes a change for transmission. */
    public static WireChange encode(Change change) {
        Map<String, String> fields = new LinkedHashMap<>();
        // LinkedHashMap, not Map.of or a stream collector: both reject null values, and a
        // null here is the meaningful "clear this field" instruction.
        change.fields().forEach((name, value) -> fields.put(name, value.raw()));

        return new WireChange(
                change.opId(),
                change.entityType().name(),
                change.entityId(),
                change.kind().name(),
                change.hlc().toCompactString(),
                fields);
    }

    /**
     * Decodes a received change, validating it.
     *
     * @throws IllegalArgumentException if any component is missing or unrecognised
     */
    public static Change decode(WireChange wire) {
        if (wire == null) {
            throw new IllegalArgumentException("Change must not be null");
        }
        if (wire.opId() == null) {
            throw new IllegalArgumentException("Change is missing an opId");
        }
        if (wire.entityId() == null) {
            throw new IllegalArgumentException("Op " + wire.opId() + " is missing an entityId");
        }

        EntityType type = parseEnum(EntityType.class, wire.entityType(), "entityType", wire.opId());
        OpKind kind = parseEnum(OpKind.class, wire.kind(), "kind", wire.opId());

        if (wire.hlc() == null || wire.hlc().isEmpty()) {
            throw new IllegalArgumentException("Op " + wire.opId() + " is missing an hlc");
        }
        Hlc hlc;
        try {
            hlc = Hlc.parse(wire.hlc());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Malformed hlc on op %s: %s".formatted(wire.opId(), wire.hlc()), e);
        }

        Map<String, FieldValue> fields = new LinkedHashMap<>();
        wire.fields().forEach((name, value) -> fields.put(name, FieldValue.of(value)));

        // Change's own constructor enforces the remaining invariant — that DELETE and
        // RESTORE carry no field writes — so that rule has exactly one home too.
        return new Change(wire.opId(), type, wire.entityId(), kind, hlc, fields);
    }

    /**
     * Unknown enum values are a rejected request, not a server fault.
     *
     * <p>A newer client sending an entity type this version does not know is a protocol
     * mismatch the client can act on by upgrading.
     */
    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String raw, String what, Object opId) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Op " + opId + " is missing a " + what);
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown %s on op %s: %s".formatted(what, opId, raw), e);
        }
    }
}
