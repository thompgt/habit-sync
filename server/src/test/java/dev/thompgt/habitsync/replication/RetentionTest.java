package dev.thompgt.habitsync.replication;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thompgt.habitsync.auth.AuthResult;
import dev.thompgt.habitsync.auth.AuthService;
import dev.thompgt.habitsync.replication.dto.SyncDtos;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncRequest;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncResponse;
import dev.thompgt.habitsync.security.AuthenticatedUser;
import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import dev.thompgt.habitsync.sync.WireChange;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-003's retention job, and the property that makes it safe to run at all: a device is
 * never served an incomplete change set. Either it can be caught up from what survives, or
 * it is told to bootstrap.
 *
 * <p>Cutoffs are passed explicitly rather than by waiting 90 days. A cutoff in the future
 * makes every existing row collectable, which is the same situation as an old cutoff
 * against old rows and takes a fraction of a second to arrange.
 */
class RetentionTest extends AbstractIntegrationTest {

    @Autowired private SyncService syncService;
    @Autowired private AuthService authService;
    @Autowired private RetentionService retention;
    @Autowired private ChangeLogRepository changeLog;
    @Autowired private EntityRepository entities;

    private record Account(UUID userId, AuthenticatedUser deviceA, AuthenticatedUser deviceB) {}

    private Account twoDeviceAccount() {
        String email = "retention-" + UUID.randomUUID() + "@example.com";
        AuthResult first = authService.register(email, "correct-horse-battery-staple", "Phone");
        AuthResult second = authService.login(email, "correct-horse-battery-staple", "Tablet", null);
        return new Account(
                first.userId(),
                new AuthenticatedUser(first.userId(), first.deviceId()),
                new AuthenticatedUser(second.userId(), second.deviceId()));
    }

    private SyncResponse sync(AuthenticatedUser device, long sinceSeq, WireChange... ops) {
        return syncService.sync(
                device, new SyncRequest(sinceSeq, SyncDtos.PROTOCOL_VERSION, List.of(ops)));
    }

    private static WireChange upsert(UUID entityId, String hlc, Map<String, String> fields) {
        return new WireChange(UUID.randomUUID(), "HABIT", entityId, "UPSERT", hlc, fields);
    }

    private static WireChange delete(UUID entityId, String hlc) {
        return new WireChange(UUID.randomUUID(), "HABIT", entityId, "DELETE", hlc, Map.of());
    }

    /** Late enough that every row written by the test counts as expired. */
    private static Instant collectEverything() {
        return Instant.now().plus(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("collection removes the log but not the state it summarises")
    void collectedAccountStillBootstrapsCompletely() {
        Account account = twoDeviceAccount();
        UUID running = UUID.randomUUID();
        UUID reading = UUID.randomUUID();

        sync(account.deviceA(), 0, upsert(running, "1000:0:device-a", Map.of("name", "Run")));
        sync(account.deviceA(), 1, upsert(running, "2000:0:device-a", Map.of("name", "Jog")));
        sync(account.deviceA(), 2, upsert(reading, "3000:0:device-a", Map.of("name", "Read")));

        RetentionService.Collected collected = retention.collectFor(account.userId(), collectEverything());
        assertThat(collected.logRows()).isEqualTo(3);
        assertThat(changeLog.oldestRetainedSequence(account.userId())).isEmpty();

        // This is the whole point of serving bootstraps from state. Under log replay the
        // new device would receive nothing and conclude the account was empty.
        SyncResponse boot = sync(account.deviceB(), 0);
        assertThat(boot.changes()).hasSize(2);
        assertThat(boot.changes())
                .anySatisfy(c -> assertThat(c.change().fields()).containsEntry("name", "Jog"))
                .anySatisfy(c -> assertThat(c.change().fields()).containsEntry("name", "Read"));
    }

    @Test
    @DisplayName("a device left below the horizon is told to resync, never served a partial page")
    void deviceBehindTheHorizonIsSentToBootstrap() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        sync(account.deviceA(), 0, upsert(habit, "1000:0:device-a", Map.of("name", "Run")));
        sync(account.deviceA(), 1, upsert(habit, "2000:0:device-a", Map.of("colour", "red")));

        retention.collectFor(account.userId(), collectEverything());

        // Device B saw seq 1 and then went quiet. Everything it had not read is gone.
        SyncResponse response = sync(account.deviceB(), 1);

        assertThat(response.resyncRequired()).isTrue();
        assertThat(response.resyncReason()).isEqualTo("watermarkBelowGcHorizon");
        assertThat(response.changes()).isEmpty();
    }

    @Test
    @DisplayName("collection stops at the retention boundary, leaving newer entries intact")
    void onlyThePrefixIsCollected() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        sync(account.deviceA(), 0, upsert(habit, "1000:0:device-a", Map.of("name", "Run")));
        sync(account.deviceA(), 1, upsert(habit, "2000:0:device-a", Map.of("colour", "red")));

        // A cutoff before anything was written: nothing is old enough yet.
        RetentionService.Collected none =
                retention.collectFor(account.userId(), Instant.now().minus(Duration.ofHours(1)));

        assertThat(none.logRows()).isZero();
        assertThat(changeLog.oldestRetainedSequence(account.userId())).contains(1L);

        // ...and a device that is merely behind, with nothing collected, is caught up
        // rather than sent away.
        SyncResponse response = sync(account.deviceB(), 1);
        assertThat(response.resyncRequired()).isFalse();
        assertThat(response.changes()).hasSize(1);
    }

    @Test
    @DisplayName("what survives collection is always a contiguous suffix")
    void survivingLogHasNoHoles() {
        Account account = twoDeviceAccount();
        UUID habit = UUID.randomUUID();

        for (int i = 1; i <= 5; i++) {
            sync(account.deviceA(), i - 1, upsert(habit, (i * 1000) + ":0:device-a", Map.of("f" + i, "v")));
        }

        retention.collectFor(account.userId(), collectEverything());

        // A hole would let a device below it pass the horizon check and be served the
        // entries either side, permanently missing the ones between. Contiguity is what
        // makes "oldest retained > watermark + 1" a sound question to ask.
        List<ChangeLogRepository.LoggedChange> remaining =
                changeLog.readSince(account.userId(), 0, 100);
        assertThat(remaining).isEmpty();
        assertThat(changeLog.currentSequence(account.userId()))
                .as("the counter is untouched; collection frees rows, it does not rewind history")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("expired tombstones are hard-deleted; live entities are not")
    void tombstonesAreCollectedAndLiveEntitiesSurvive() {
        Account account = twoDeviceAccount();
        UUID deleted = UUID.randomUUID();
        UUID kept = UUID.randomUUID();

        sync(account.deviceA(), 0, upsert(deleted, "1000:0:device-a", Map.of("name", "Old")));
        sync(account.deviceA(), 1, upsert(kept, "2000:0:device-a", Map.of("name", "Current")));
        sync(account.deviceA(), 2, delete(deleted, "3000:0:device-a"));

        RetentionService.Collected collected = retention.collectFor(account.userId(), collectEverything());

        assertThat(collected.tombstones()).isEqualTo(1);
        assertThat(entities.load(account.userId(), "HABIT", deleted)).isEmpty();
        assertThat(entities.load(account.userId(), "HABIT", kept)).isPresent();

        // And the tombstone is absent from a bootstrap rather than lingering as a change
        // describing an entity that no longer exists anywhere.
        assertThat(sync(account.deviceB(), 0).changes()).hasSize(1);
    }

    @Test
    void collectingAnAccountWithNothingToCollectIsANoOp() {
        Account account = twoDeviceAccount();

        RetentionService.Collected collected = retention.collectFor(account.userId(), collectEverything());

        assertThat(collected.logRows()).isZero();
        assertThat(collected.tombstones()).isZero();
    }
}
