package dev.thompgt.habitsync.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire encoding, both directions.
 *
 * <p>The null-value cases are the ones that matter. A field cleared to null is
 * indistinguishable from an untouched field under any encoding that drops nulls, and the
 * failure is silent: the user clears a habit's colour, the app shows it cleared, and the
 * change never propagates.
 */
class ChangeCodecTest {

    private static final UUID OP = UUID.fromString("0192f8a0-0000-7000-8000-0000000000ff");
    private static final UUID ENTITY = UUID.fromString("0192f8a0-0000-7000-8000-00000000000a");
    private static final Hlc HLC = new Hlc(1_700_000_000_000L, 3, "device-a");

    @Test
    void roundTripsAnUpsert() {
        Change original = Change.upsert(
                OP,
                EntityType.HABIT,
                ENTITY,
                HLC,
                Map.of("name", FieldValue.of("Run"), "target", FieldValue.of(5)));

        assertThat(ChangeCodec.decode(ChangeCodec.encode(original))).isEqualTo(original);
    }

    @Test
    void roundTripsADelete() {
        Change original = Change.delete(OP, EntityType.HABIT, ENTITY, HLC);

        WireChange wire = ChangeCodec.encode(original);

        assertThat(wire.kind()).isEqualTo("DELETE");
        assertThat(wire.fields()).isEmpty();
        assertThat(ChangeCodec.decode(wire)).isEqualTo(original);
    }

    @Test
    void roundTripsARestore() {
        Change original = Change.restore(OP, EntityType.HABIT, ENTITY, HLC);

        assertThat(ChangeCodec.decode(ChangeCodec.encode(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("a field cleared to null survives the round trip as a null, not as absent")
    void preservesClearedFields() {
        Change original = Change.upsert(
                OP, EntityType.HABIT, ENTITY, HLC, Map.of("colour", FieldValue.NULL));

        WireChange wire = ChangeCodec.encode(original);

        assertThat(wire.fields()).containsKey("colour");
        assertThat(wire.fields().get("colour")).isNull();
        assertThat(ChangeCodec.decode(wire).fields().get("colour")).isEqualTo(FieldValue.NULL);
    }

    @Test
    @DisplayName("clearing a field is distinguishable from not touching it")
    void distinguishesAClearFromAnOmission() {
        Map<String, String> cleared = new HashMap<>();
        cleared.put("colour", null);

        Change withClear = ChangeCodec.decode(
                new WireChange(OP, "HABIT", ENTITY, "UPSERT", HLC.toCompactString(), cleared));
        Change withoutColour = ChangeCodec.decode(
                new WireChange(OP, "HABIT", ENTITY, "UPSERT", HLC.toCompactString(), Map.of("name", "Run")));

        assertThat(withClear.fields()).containsKey("colour");
        assertThat(withoutColour.fields()).doesNotContainKey("colour");
    }

    @Test
    void encodesTheHlcInItsCompactForm() {
        WireChange wire = ChangeCodec.encode(Change.delete(OP, EntityType.HABIT, ENTITY, HLC));

        assertThat(wire.hlc()).isEqualTo("1700000000000:3:device-a");
        assertThat(Hlc.parse(wire.hlc())).isEqualTo(HLC);
    }

    @Test
    void rejectsAnUnknownEntityType() {
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(OP, "NOT_A_TYPE", ENTITY, "UPSERT", HLC.toCompactString(), Map.of("a", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown entityType")
                .hasMessageContaining(OP.toString());
    }

    @Test
    void rejectsAnUnknownOpKind() {
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(OP, "HABIT", ENTITY, "MERGE_PLEASE", HLC.toCompactString(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown kind");
    }

    @Test
    void rejectsAMalformedHlc() {
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(OP, "HABIT", ENTITY, "UPSERT", "not-an-hlc", Map.of("a", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed hlc");
    }

    @Test
    void rejectsMissingComponents() {
        String hlc = HLC.toCompactString();

        assertThatThrownBy(() -> ChangeCodec.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(null, "HABIT", ENTITY, "UPSERT", hlc, Map.of("a", "b"))))
                .hasMessageContaining("opId");
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(OP, "HABIT", null, "UPSERT", hlc, Map.of("a", "b"))))
                .hasMessageContaining("entityId");
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(OP, "HABIT", ENTITY, "UPSERT", null, Map.of("a", "b"))))
                .hasMessageContaining("hlc");
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(OP, null, ENTITY, "UPSERT", hlc, Map.of("a", "b"))))
                .hasMessageContaining("entityType");
    }

    @Test
    @DisplayName("a DELETE carrying field writes is rejected rather than half-applied")
    void rejectsFieldsOnALifecycleOp() {
        assertThatThrownBy(() -> ChangeCodec.decode(
                        new WireChange(OP, "HABIT", ENTITY, "DELETE", HLC.toCompactString(), Map.of("name", "Run"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry field writes");
    }

    @Test
    void treatsAnAbsentFieldMapAsEmpty() {
        Change decoded = ChangeCodec.decode(
                new WireChange(OP, "HABIT", ENTITY, "DELETE", HLC.toCompactString(), null));

        assertThat(decoded.fields()).isEmpty();
        assertThat(decoded.kind()).isEqualTo(OpKind.DELETE);
    }
}
