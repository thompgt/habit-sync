package dev.thompgt.habitsync.sync;

/**
 * A single field's value, already serialised to text by the storage layer.
 *
 * <p>This wrapper exists to make "set this field to null" expressible. A bare
 * {@code Map<String, String>} cannot distinguish <em>"this change does not touch the
 * field"</em> (key absent) from <em>"this change clears the field"</em> (key present,
 * value null) — {@link java.util.Map#copyOf} rejects null values outright, and
 * {@code get() == null} is ambiguous even where they are allowed. Conflating the two
 * means a user clearing a habit's colour would be silently treated as not having
 * touched it, and their edit would never propagate.
 *
 * <p>sync-core stays deliberately type-agnostic: converting an {@code int}, an
 * {@code Instant}, or an enum to and from this text form is the storage layer's job on
 * each platform. The engine only ever compares and copies these values, never
 * interprets them.
 *
 * <p>One consequence worth stating: the text encoding must be <b>canonical</b>. Two
 * devices writing the same logical value must produce byte-identical strings, or
 * convergence checks will report false divergence. Store weights as integer grams, not
 * floating-point kilograms.
 *
 * @param raw the serialised value, or {@code null} to represent SQL NULL
 */
public record FieldValue(String raw) {

    /** The explicit "field is null" value. */
    public static final FieldValue NULL = new FieldValue(null);

    public static FieldValue of(String raw) {
        return raw == null ? NULL : new FieldValue(raw);
    }

    public static FieldValue of(long value) {
        return new FieldValue(Long.toString(value));
    }

    public static FieldValue of(boolean value) {
        return new FieldValue(Boolean.toString(value));
    }

    public boolean isNull() {
        return raw == null;
    }

    @Override
    public String toString() {
        return raw == null ? "<null>" : raw;
    }
}
