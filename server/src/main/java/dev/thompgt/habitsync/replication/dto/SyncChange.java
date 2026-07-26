package dev.thompgt.habitsync.replication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * A change in wire form.
 *
 * <p>Deliberately stringly-typed rather than reusing {@code sync-core}'s {@code Change}
 * directly. The wire format is a compatibility surface that outlives any single client
 * version: enums arrive as strings so an unknown value from a newer client is a
 * validation error rather than a deserialisation crash, and the HLC is its compact
 * {@code physical:logical:node} encoding so it occupies one column and one JSON field.
 *
 * @param fields field writes for {@code UPSERT}; empty for {@code DELETE}/{@code RESTORE}.
 *              A {@code null} value is meaningful — it clears the field, as distinct from
 *              the key being absent, which leaves it untouched.
 */
public record SyncChange(
        @NotNull UUID opId,
        @NotBlank String entityType,
        @NotNull UUID entityId,
        @NotBlank String kind,
        @NotBlank String hlc,
        Map<String, String> fields) {

    public SyncChange {
        // Not Map.copyOf: null values are legitimate and copyOf rejects them.
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }
}
