package dev.thompgt.habitsync.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thompgt.habitsync.auth.AuthResult;
import dev.thompgt.habitsync.auth.AuthService;
import dev.thompgt.habitsync.sync.ClockDriftException;
import dev.thompgt.habitsync.sync.WireChange;
import java.time.Duration;
import dev.thompgt.habitsync.replication.dto.SyncDtos;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncRequest;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncResponse;
import dev.thompgt.habitsync.security.AuthenticatedUser;
import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end behaviour of {@code /v1/sync}: idempotent push, incremental pull, and the
 * two-device conflict scenarios from ADR-001.
 */
class SyncProtocolTest extends AbstractIntegrationTest {

    @Autowired private SyncService syncService;
    @Autowired private AuthService authService;

    private record Account(UUID userId, AuthenticatedUser deviceA, AuthenticatedUser deviceB) {}

    /** One user, two devices — the whole point of the system. */
    private Account twoDeviceAccount() {
        String email = "sync-" + UUID.randomUUID() + "@example.com";
        AuthResult first = authService.register(email, "correct-horse-battery-staple", "Phone");
        AuthResult second = authService.login(email, "correct-horse-battery-staple", "Tablet", null);

        return new Account(
                first.userId(),
                new AuthenticatedUser(first.userId(), first.deviceId()),
                new AuthenticatedUser(second.userId(), second.deviceId()));
    }

    private static WireChange upsert(UUID entityId, String hlc, Map<String, String> fields) {
        return new WireChange(UUID.randomUUID(), "HABIT", entityId, "UPSERT", hlc, fields);
    }

    private static WireChange delete(UUID entityId, String hlc) {
        return new WireChange(UUID.randomUUID(), "HABIT", entityId, "DELETE", hlc, Map.of());
    }

    private SyncResponse push(AuthenticatedUser device, long sinceSeq, WireChange... ops) {
        return syncService.sync(
                device, new SyncRequest(sinceSeq, SyncDtos.PROTOCOL_VERSION, List.of(ops)));
    }

    private SyncResponse pull(AuthenticatedUser device, long sinceSeq) {
        return syncService.sync(device, new SyncRequest(sinceSeq, SyncDtos.PROTOCOL_VERSION, List.of()));
    }

    @Test
    void pushedChangesComeBackToTheOtherDevice() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        push(account.deviceA(), 0, upsert(habit, "1000:0:a", Map.of("name", "Run")));
        SyncResponse onB = pull(account.deviceB(), 0);

        assertThat(onB.changes()).hasSize(1);
        assertThat(onB.changes().get(0).change().fields()).containsEntry("name", "Run");
        assertThat(onB.nextSeq()).isEqualTo(1L);
        assertThat(onB.hasMore()).isFalse();
    }

    @Test
    @DisplayName("replaying a push is a no-op — this is what makes a timed-out request safe to retry")
    void pushIsIdempotent() {
        Account account = twoDeviceAccount();
        WireChange op = upsert(UUID.randomUUID(), "1000:0:a", Map.of("name", "Run"));

        SyncResponse first = push(account.deviceA(), 0, op);
        SyncResponse replay = push(account.deviceA(), 0, op);

        assertThat(first.appliedOpIds()).containsExactly(op.opId());
        // The replay is acknowledged so the client can clear its pending queue...
        assertThat(replay.appliedOpIds()).containsExactly(op.opId());
        // ...but nothing was appended a second time.
        assertThat(pull(account.deviceB(), 0).changes()).hasSize(1);
    }

    @Test
    void duplicateOpIdsWithinOneRequestAreAppliedOnce() {
        Account account = twoDeviceAccount();
        WireChange op = upsert(UUID.randomUUID(), "1000:0:a", Map.of("name", "Run"));

        push(account.deviceA(), 0, op, op);

        assertThat(pull(account.deviceB(), 0).changes()).hasSize(1);
    }

    @Test
    @DisplayName("concurrent edits to different fields both survive (ADR-001)")
    void concurrentEditsToDifferentFieldsBothSurvive() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        push(account.deviceA(), 0, upsert(habit, "1000:0:a", Map.of("name", "Run")));

        // Both devices go offline and edit different fields, neither seeing the other.
        push(account.deviceA(), 1, upsert(habit, "2000:0:device-a", Map.of("name", "Morning Run")));
        push(account.deviceB(), 1, upsert(habit, "2001:0:device-b", Map.of("targetPerWeek", "5")));

        Map<String, String> merged = latestFieldValues(account, habit);

        assertThat(merged).containsEntry("name", "Morning Run").containsEntry("targetPerWeek", "5");
    }

    @Test
    @DisplayName("same-field conflict resolves to the higher HLC, not to arrival order")
    void sameFieldConflictResolvesByHlc() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        // The LATER edit arrives FIRST. Arrival order must not decide the winner.
        push(account.deviceA(), 0, upsert(habit, "5000:0:device-a", Map.of("name", "Winner")));
        push(account.deviceB(), 0, upsert(habit, "1000:0:device-b", Map.of("name", "Loser")));

        assertThat(latestFieldValues(account, habit)).containsEntry("name", "Winner");
    }

    @Test
    @DisplayName("a delete hides the entity but does not discard concurrent field writes")
    void deleteIsOrthogonalToFieldWrites() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        push(account.deviceA(), 0, delete(habit, "1000:0:device-a"));
        push(account.deviceB(), 0, upsert(habit, "5000:0:device-b", Map.of("name", "Jog")));

        assertThat(entityDeleted(account, habit)).isTrue();
        // The field register still advanced -- required for commutativity, see ADR-003.
        assertThat(latestFieldValues(account, habit)).containsEntry("name", "Jog");
    }

    @Test
    void pullIsIncrementalFromTheWatermark() {
        Account account = twoDeviceAccount();

        push(account.deviceA(), 0, upsert(UUID.randomUUID(), "1000:0:a", Map.of("name", "One")));
        push(account.deviceA(), 0, upsert(UUID.randomUUID(), "1001:0:a", Map.of("name", "Two")));

        SyncResponse fromScratch = pull(account.deviceB(), 0);
        assertThat(fromScratch.changes()).hasSize(2);

        SyncResponse incremental = pull(account.deviceB(), fromScratch.nextSeq());
        assertThat(incremental.changes()).isEmpty();
        assertThat(incremental.nextSeq()).isEqualTo(fromScratch.nextSeq());
    }

    @Test
    @DisplayName("nextSeq reports the last change delivered, never the server head")
    void nextSeqNeverRunsAheadOfWhatWasDelivered() {
        Account account = twoDeviceAccount();
        push(account.deviceA(), 0, upsert(UUID.randomUUID(), "1000:0:a", Map.of("name", "One")));

        // Device B pulls nothing new; its watermark must not jump to the server head.
        SyncResponse response = pull(account.deviceB(), 1);

        assertThat(response.changes()).isEmpty();
        assertThat(response.nextSeq()).isEqualTo(1L);
    }

    @Test
    void aClearedFieldSurvivesTheRoundTrip() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        Map<String, String> withNull = new HashMap<>();
        withNull.put("colour", null);

        push(account.deviceA(), 0, upsert(habit, "1000:0:a", Map.of("colour", "red")));
        push(account.deviceA(), 0, upsert(habit, "2000:0:a", withNull));

        SyncResponse onB = pull(account.deviceB(), 0);
        var lastChange = onB.changes().get(onB.changes().size() - 1).change();

        // The key must be present with a null value -- "cleared", not "untouched".
        assertThat(lastChange.fields()).containsKey("colour");
        assertThat(lastChange.fields().get("colour")).isNull();
    }

    @Test
    void anEmptyPushIsJustAPull() {
        Account account = twoDeviceAccount();
        push(account.deviceA(), 0, upsert(UUID.randomUUID(), "1000:0:a", Map.of("name", "Run")));

        SyncResponse response = pull(account.deviceB(), 0);

        assertThat(response.appliedOpIds()).isEmpty();
        assertThat(response.changes()).hasSize(1);
        assertThat(response.resyncRequired()).isFalse();
    }

    @Test
    @DisplayName("both devices converge to identical state regardless of who syncs first")
    void convergenceIsIndependentOfSyncOrder() {
        UUID habit = UUID.randomUUID();

        // Two separate accounts run the same scenario with the sync order reversed.
        Account first = twoDeviceAccount();
        push(first.deviceA(), 0, upsert(habit, "1000:0:device-a", Map.of("name", "A wins")));
        push(first.deviceB(), 0, upsert(habit, "2000:0:device-b", Map.of("targetPerWeek", "5")));

        Account second = twoDeviceAccount();
        push(second.deviceB(), 0, upsert(habit, "2000:0:device-b", Map.of("targetPerWeek", "5")));
        push(second.deviceA(), 0, upsert(habit, "1000:0:device-a", Map.of("name", "A wins")));

        assertThat(latestFieldValues(second, habit)).isEqualTo(latestFieldValues(first, habit));
        assertThat(entityDeleted(second, habit)).isEqualTo(entityDeleted(first, habit));
    }

    // ------------------------------------------------------------- bootstrap

    @Test
    @DisplayName("a device starting from zero gets current state, not a replay of history")
    void bootstrapCollapsesSupersededHistory() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        push(account.deviceA(), 0, upsert(habit, "1000:0:device-a", Map.of("name", "Run")));
        push(account.deviceA(), 1, upsert(habit, "2000:0:device-a", Map.of("name", "Jog")));
        push(account.deviceA(), 2, upsert(habit, "3000:0:device-a", Map.of("name", "Sprint")));

        SyncResponse boot = pull(account.deviceB(), 0);

        // Three log entries, one surviving register. The intermediate names are history the
        // device has no use for -- and replaying them is what forces the log to be kept
        // back to the account's first write forever.
        assertThat(boot.changes()).hasSize(1);
        assertThat(boot.changes().get(0).change().fields()).containsEntry("name", "Sprint");
        assertThat(boot.nextSeq()).isEqualTo(3L);
    }

    @Test
    @DisplayName("each field keeps its own HLC through a bootstrap, not one clock per entity")
    void bootstrapPreservesPerFieldClocks() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        push(account.deviceA(), 0, upsert(habit, "1000:0:device-a", Map.of("name", "Run")));
        push(account.deviceB(), 0, upsert(habit, "5000:0:device-b", Map.of("targetPerWeek", "3")));

        SyncResponse boot = pull(account.deviceA(), 0);

        // Flattening these onto one clock would hand the device false provenance, and its
        // next conflict on the older field would then resolve the wrong way.
        Map<String, String> clockByField = new HashMap<>();
        boot.changes()
                .forEach(c -> c.change().fields().keySet().forEach(f -> clockByField.put(f, c.change().hlc())));

        assertThat(clockByField).containsEntry("name", "1000:0:device-a");
        assertThat(clockByField).containsEntry("targetPerWeek", "5000:0:device-b");
    }

    @Test
    @DisplayName("a bootstrap carries tombstones, or the new device shows deleted entities")
    void bootstrapIncludesTombstones() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        push(account.deviceA(), 0, upsert(habit, "1000:0:device-a", Map.of("name", "Run")));
        push(account.deviceA(), 1, delete(habit, "2000:0:device-a"));

        SyncResponse boot = pull(account.deviceB(), 0);

        assertThat(boot.changes())
                .as("the field register survives the tombstone (ADR-003), so both are sent")
                .hasSize(2);
        assertThat(boot.changes())
                .anySatisfy(c -> assertThat(c.change().kind()).isEqualTo("DELETE"));
    }

    @Test
    void bootstrappingAnEmptyAccountReturnsNothing() {
        Account account = twoDeviceAccount();

        SyncResponse boot = pull(account.deviceA(), 0);

        assertThat(boot.changes()).isEmpty();
        assertThat(boot.nextSeq()).isZero();
        assertThat(boot.resyncRequired()).isFalse();
    }

    // ------------------------------------------------------- clock plausibility

    @Test
    @DisplayName("a timestamp years ahead is refused rather than absorbed into the log")
    void pushWithImplausibleClockIsRejected() {
        Account account = twoDeviceAccount();
        long farFuture = System.currentTimeMillis() + Duration.ofDays(365).toMillis();

        assertThatThrownBy(() ->
                        push(account.deviceA(), 0, upsert(UUID.randomUUID(), farFuture + ":0:device-a", Map.of("name", "Run"))))
                .isInstanceOf(ClockDriftException.class);
    }

    @Test
    @DisplayName("rejecting one op rejects its whole batch, so nothing lands half-applied")
    void oneSkewedOpFailsTheEntireBatch() {
        Account account = twoDeviceAccount();
        UUID honest = UUID.randomUUID();
        long farFuture = System.currentTimeMillis() + Duration.ofDays(365).toMillis();

        assertThatThrownBy(() -> push(
                        account.deviceA(),
                        0,
                        upsert(honest, "1000:0:device-a", Map.of("name", "Run")),
                        upsert(UUID.randomUUID(), farFuture + ":0:device-a", Map.of("name", "Skewed"))))
                .isInstanceOf(ClockDriftException.class);

        // The transaction rolled back whole. A partially-accepted batch would leave the
        // client unable to say which of its ops it may clear from the outbox.
        assertThat(pull(account.deviceB(), 0).changes()).isEmpty();
        assertThat(latestFieldValues(account, honest)).isEmpty();
    }

    @Test
    @DisplayName("a device back from a long trip offline pushes old timestamps and is not punished")
    void timestampsFarInThePastAreAccepted() {
        Account account = twoDeviceAccount();
        long lastYear = System.currentTimeMillis() - Duration.ofDays(365).toMillis();

        push(account.deviceA(), 0, upsert(UUID.randomUUID(), lastYear + ":0:device-a", Map.of("name", "Run")));

        assertThat(pull(account.deviceB(), 0).changes()).hasSize(1);
    }

    @Test
    @DisplayName("ordinary jitter inside the tolerance still syncs")
    void smallSkewIsAbsorbed() {
        Account account = twoDeviceAccount();
        long slightlyAhead =
                System.currentTimeMillis() + SyncService.MAX_CLOCK_DRIFT.toMillis() - Duration.ofSeconds(30).toMillis();

        push(account.deviceA(), 0, upsert(UUID.randomUUID(), slightlyAhead + ":0:device-a", Map.of("name", "Run")));

        assertThat(pull(account.deviceB(), 0).changes()).hasSize(1);
    }

    // -------------------------------------------------------------- helpers

    @Autowired private EntityRepository entities;

    private Map<String, String> latestFieldValues(Account account, UUID entityId) {
        return entities
                .load(account.userId(), "HABIT", entityId)
                .map(EntityRepository.StoredEntity::fieldValues)
                .orElse(Map.of());
    }

    private boolean entityDeleted(Account account, UUID entityId) {
        return entities
                .load(account.userId(), "HABIT", entityId)
                .map(EntityRepository.StoredEntity::deleted)
                .orElse(false);
    }
}
