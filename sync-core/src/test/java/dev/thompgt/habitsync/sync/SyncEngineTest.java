package dev.thompgt.habitsync.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The client engine against a {@link FakeServer}, exercising the paths a real network
 * makes hard to reach: a push whose response is lost, a duplicated page, a server that
 * demands a resync, a device whose clock has been rolled back.
 *
 * <p>The convergence cases here use two devices and are the point of the whole project.
 * They are still hand-built scenarios; M6 generalises them to N devices under randomised
 * partitions.
 */
class SyncEngineTest {

    private static final UUID HABIT = UUID.fromString("0192f8a0-0000-7000-8000-00000000000a");
    private static final EntityKey KEY = new EntityKey(EntityType.HABIT, HABIT);

    /** A clock the test moves by hand, so skew and rollback are expressible. */
    private static final class MutableTime implements TimeSource {
        private long millis = 1_700_000_000_000L;

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        void advance(long by) {
            millis += by;
        }

        void set(long to) {
            millis = to;
        }
    }

    /** A device: its store, its clock, and its engine, all sharing one server. */
    private static final class Device {
        final InMemoryLocalStore store = new InMemoryLocalStore();
        final MutableTime time = new MutableTime();
        final SyncEngine engine;

        Device(String nodeId, Transport transport) {
            this.engine = new SyncEngine(new HlcClock(nodeId, time), store, transport);
        }

        String name() {
            EntityRecord record = store.load(KEY).orElse(null);
            return record == null || record.field("name") == null ? null : record.field("name").raw();
        }

        boolean visible() {
            return store.load(KEY).map(EntityRecord::visible).orElse(false);
        }
    }

    private final FakeServer server = new FakeServer();
    private final Device deviceA = new Device("device-a", server);
    private final Device deviceB = new Device("device-b", server);

    @Nested
    @DisplayName("local edits")
    class LocalEdits {

        @Test
        void applyImmediatelyWithoutTouchingTheNetwork() {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));

            assertThat(deviceA.name()).isEqualTo("Run");
            assertThat(deviceA.engine.pendingOpCount()).isEqualTo(1);
            assertThat(server.exchangeCount()).isZero();
        }

        @Test
        void queueUpWhileOfflineAndPushInOneRoundTrip() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "target", FieldValue.of(5));
            deviceA.engine.delete(EntityType.EXERCISE, UUID.randomUUID());

            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.opsAcknowledged()).isEqualTo(3);
            assertThat(outcome.moreRemaining()).isFalse();
            assertThat(deviceA.engine.pendingOpCount()).isZero();
            assertThat(server.logSize()).isEqualTo(3);
        }

        @Test
        void rejectAnUpsertThatWritesNothing() {
            assertThatThrownBy(() -> deviceA.engine.upsert(EntityType.HABIT, HABIT, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one field");
        }

        @Test
        void areStampedInStrictlyIncreasingOrderEvenWithinOneMillisecond() {
            Change first = deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("a"));
            Change second = deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("b"));

            assertThat(second.hlc().isAfter(first.hlc())).isTrue();
            assertThat(deviceA.name()).isEqualTo("b");
        }
    }

    @Nested
    @DisplayName("two devices")
    class Convergence {

        @Test
        void bothEditsSurviveWhenTheyTouchDifferentFields() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));
            deviceA.engine.sync();
            deviceB.engine.sync();

            // Both go offline and edit disjoint fields of the same habit.
            deviceA.time.advance(1_000);
            deviceB.time.advance(1_000);
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Morning run"));
            deviceB.engine.upsert(EntityType.HABIT, HABIT, "target", FieldValue.of(5));

            // Reconnect, in the least convenient order: B pushes first, then both drain.
            deviceB.engine.sync();
            deviceA.engine.sync();
            deviceB.engine.sync();
            deviceA.engine.sync();

            // Per-field LWW is what saves both. Per-row would have discarded one.
            assertThat(deviceA.name()).isEqualTo("Morning run");
            assertThat(deviceB.name()).isEqualTo("Morning run");
            assertThat(deviceA.store.load(KEY).orElseThrow().field("target"))
                    .isEqualTo(FieldValue.of(5));
            assertThat(deviceA.store.load(KEY)).isEqualTo(deviceB.store.load(KEY));
        }

        @Test
        void agreeOnTheWinnerWhenTheyEditTheSameField() throws Exception {
            deviceA.time.advance(5_000); // A's edit is genuinely later
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From A"));
            deviceB.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From B"));

            deviceB.engine.sync();
            deviceA.engine.sync();
            deviceB.engine.sync();
            deviceA.engine.sync();

            assertThat(deviceA.name()).isEqualTo("From A");
            assertThat(deviceB.name()).isEqualTo("From A");
        }

        @Test
        void convergeOnDeleteBeatingAConcurrentEdit() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));
            deviceA.engine.sync();
            deviceB.engine.sync();

            deviceB.time.advance(2_000);
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Renamed"));
            deviceB.engine.delete(EntityType.HABIT, HABIT);

            deviceA.engine.sync();
            deviceB.engine.sync();
            deviceA.engine.sync();
            deviceB.engine.sync();

            // The rename is retained in the field registers but the entity stays hidden:
            // the two register groups are orthogonal, which is what keeps this commutative.
            assertThat(deviceA.visible()).isFalse();
            assertThat(deviceB.visible()).isFalse();
            assertThat(deviceA.name()).isEqualTo("Renamed");
            assertThat(deviceA.store.load(KEY)).isEqualTo(deviceB.store.load(KEY));
        }

        @Test
        void endUpWithIdenticalStateRegardlessOfSyncOrder() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("A1"));
            deviceB.engine.upsert(EntityType.HABIT, HABIT, "colour", FieldValue.of("blue"));
            deviceA.time.advance(10);
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "target", FieldValue.of(3));
            deviceB.time.advance(20);
            deviceB.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("B2"));

            for (int round = 0; round < 3; round++) {
                deviceA.engine.sync();
                deviceB.engine.sync();
            }

            assertThat(deviceA.store.load(KEY)).isEqualTo(deviceB.store.load(KEY));
            assertThat(deviceA.engine.pendingOpCount()).isZero();
            assertThat(deviceB.engine.pendingOpCount()).isZero();
        }
    }

    @Nested
    @DisplayName("delivery hazards")
    class DeliveryHazards {

        @Test
        void aDuplicatedPageIsAppliedOnce() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));
            deviceA.engine.sync();

            FakeServer duplicating = new FakeServer().duplicateEveryPage();
            Device fresh = new Device("device-c", duplicating);
            duplicating.receiveFrom(
                    Change.upsert(
                            UUID.randomUUID(),
                            EntityType.HABIT,
                            HABIT,
                            new Hlc(1_700_000_001_000L, 0, "device-a"),
                            Map.of("name", FieldValue.of("Run"))));

            fresh.engine.sync();
            EntityRecord afterFirst = fresh.store.load(KEY).orElseThrow();

            fresh.engine.sync();

            assertThat(fresh.store.load(KEY).orElseThrow()).isEqualTo(afterFirst);
            assertThat(fresh.name()).isEqualTo("Run");
        }

        @Test
        void aReplayedPushIsAcknowledgedRatherThanRelogged() throws Exception {
            Change op = deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));

            // The server commits it, then the response is lost in transit.
            server.receiveFrom(op);
            assertThat(server.logSize()).isEqualTo(1);

            // The client, none the wiser, retries the same op id.
            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.opsAcknowledged()).isEqualTo(1);
            assertThat(deviceA.engine.pendingOpCount()).isZero();
            assertThat(server.logSize()).as("the replay must not be logged twice").isEqualTo(1);
        }

        @Test
        void aFailedRoundTripLosesNothing() {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));
            server.failNext(1);

            assertThatThrownBy(deviceA.engine::sync)
                    .isInstanceOf(TransportException.class)
                    .matches(e -> ((TransportException) e).isRetryable());

            assertThat(deviceA.engine.pendingOpCount()).isEqualTo(1);
            assertThat(deviceA.store.watermark()).isZero();
            assertThat(deviceA.name()).as("the local edit is still there").isEqualTo("Run");
        }

        @Test
        void opsThisDeviceNeverPushedAreNotClearedFromTheOutbox() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));

            // A server naming somebody else's op ids must not empty our outbox.
            server.alsoAcknowledge(Set.of(UUID.randomUUID(), UUID.randomUUID()));
            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.opsAcknowledged()).isEqualTo(1);
            assertThat(deviceA.engine.pendingOpCount()).isZero();
        }

        @Test
        void aCursorThatDoesNotAdvanceIsRejectedRatherThanLoopedOn() {
            server.receiveFrom(
                    Change.upsert(
                            UUID.randomUUID(),
                            EntityType.HABIT,
                            HABIT,
                            new Hlc(1_700_000_001_000L, 0, "device-b"),
                            Map.of("name", FieldValue.of("Run"))));
            server.freezeCursor();

            assertThatThrownBy(deviceA.engine::sync)
                    .isInstanceOf(TransportException.class)
                    .hasMessageContaining("did not advance")
                    .matches(e -> !((TransportException) e).isRetryable());
        }
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        void drainsABacklogAcrossPages() throws Exception {
            for (int i = 0; i < 25; i++) {
                server.receiveFrom(
                        Change.upsert(
                                UUID.randomUUID(),
                                EntityType.HABIT,
                                UUID.randomUUID(),
                                new Hlc(1_700_000_000_000L + i, 0, "device-b"),
                                Map.of("name", FieldValue.of("habit-" + i))));
            }
            server.pageSize(10);

            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.changesApplied()).isEqualTo(25);
            assertThat(outcome.pagesFetched()).isEqualTo(3);
            assertThat(outcome.moreRemaining()).isFalse();
            assertThat(deviceA.store.watermark()).isEqualTo(25);
            assertThat(deviceA.store.allRecords()).hasSize(25);
        }

        @Test
        void yieldsAfterItsPageBudgetRatherThanDrainingInOneBurst() throws Exception {
            for (int i = 0; i < 30; i++) {
                server.receiveFrom(
                        Change.upsert(
                                UUID.randomUUID(),
                                EntityType.HABIT,
                                UUID.randomUUID(),
                                new Hlc(1_700_000_000_000L + i, 0, "device-b"),
                                Map.of("name", FieldValue.of("habit-" + i))));
            }
            server.pageSize(5);

            SyncEngine bounded =
                    new SyncEngine(
                            new HlcClock("device-a", deviceA.time),
                            deviceA.store,
                            server,
                            new MergeEngine(),
                            SyncEngine.DEFAULT_PUSH_BATCH_SIZE,
                            2);

            SyncOutcome outcome = bounded.sync();

            assertThat(outcome.pagesFetched()).isEqualTo(2);
            assertThat(outcome.changesApplied()).isEqualTo(10);
            assertThat(outcome.moreRemaining()).as("the caller is told to come back").isTrue();

            // Watermark is exactly what was applied, so resuming picks up with no gap.
            assertThat(deviceA.store.watermark()).isEqualTo(10);
            assertThat(bounded.sync().changesApplied()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("resync")
    class Resync {

        @Test
        void wipesEntityStateAndRebuildsFromZero() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));
            deviceA.engine.sync();
            assertThat(deviceA.store.watermark()).isEqualTo(1);

            // A habit this device knows about locally but which the server has since
            // garbage-collected the log for.
            UUID stale = UUID.randomUUID();
            deviceA.engine.upsert(EntityType.HABIT, stale, "name", FieldValue.of("Stale"));
            deviceA.engine.sync();

            server.demandResync(1);
            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.resynced()).isTrue();
            // Rebuilt from the server's log rather than trusted from local state.
            assertThat(deviceA.store.watermark()).isEqualTo(server.logSize());
            assertThat(deviceA.name()).isEqualTo("Run");
        }

        @Test
        void keepsUnpushedLocalWork() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Offline edit"));
            server.demandResync(1);

            SyncOutcome outcome = deviceA.engine.sync();

            // The op was pushed and acknowledged in the same exchange that demanded the
            // resync, so it survives the wipe by way of the server's log.
            assertThat(outcome.resynced()).isTrue();
            assertThat(deviceA.engine.pendingOpCount()).isZero();
            assertThat(deviceA.name()).isEqualTo("Offline edit");
        }

        @Test
        void anOfflineOutboxSurvivesAWipeItWasNotPartOf() {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Precious"));
            assertThat(deviceA.engine.pendingOpCount()).isEqualTo(1);

            deviceA.store.resetForResync();

            assertThat(deviceA.store.watermark()).isZero();
            assertThat(deviceA.store.allRecords()).isEmpty();
            assertThat(deviceA.engine.pendingOpCount())
                    .as("a week of offline work is not the server's to discard")
                    .isEqualTo(1);
        }

        @Test
        void refusesToLoopWhenResettingDoesNotSatisfyTheServer() {
            server.demandResync(5);

            assertThatThrownBy(deviceA.engine::sync)
                    .isInstanceOf(TransportException.class)
                    .hasMessageContaining("resync")
                    .matches(e -> !((TransportException) e).isRetryable());
        }
    }

    @Nested
    @DisplayName("conflict reporting")
    class Conflicts {

        /**
         * The bargain ADR-001 strikes: LWW may discard the user's edit, provided the user
         * is told. A client that cannot tell is a client that loses data silently.
         */
        @Test
        void namesTheLocalEditThatARemoteWriteOverwrote() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From A"));
            deviceA.engine.sync();

            deviceB.time.advance(5_000);
            deviceB.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From B"));
            deviceB.engine.sync();

            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.conflicts()).hasSize(1);
            Conflict conflict = outcome.conflicts().get(0);
            assertThat(conflict.kind()).isEqualTo(Conflict.Kind.FIELD_OVERWRITTEN);
            assertThat(conflict.field()).isEqualTo("name");
            assertThat(conflict.entity()).isEqualTo(KEY);
            assertThat(conflict.lostLocalWrite()).isTrue();
            assertThat(conflict.loser().nodeId()).isEqualTo("device-a");
            assertThat(conflict.winner().nodeId()).isEqualTo("device-b");
            assertThat(outcome.lostLocalWrites()).hasSize(1);
            assertThat(deviceA.name()).isEqualTo("From B");
        }

        /**
         * The other direction. Device B's pull carries A's older, losing write — real
         * information for a debug screen, but nothing this user did and nothing to
         * interrupt them over.
         */
        @Test
        void separatesAnotherDevicesLossFromThisUsersOwn() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From A"));
            deviceA.engine.sync();

            deviceB.time.advance(5_000);
            deviceB.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From B"));

            SyncOutcome outcome = deviceB.engine.sync();

            assertThat(outcome.conflicts()).hasSize(1);
            assertThat(outcome.conflicts().get(0).lostLocalWrite()).isFalse();
            assertThat(outcome.lostLocalWrites()).isEmpty();
        }

        /** ADR-003's opening scenario, from the losing device's point of view. */
        @Test
        void reportsAnEntityDeletedElsewhereThatHeldTheUsersEdits() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Evening Run"));
            deviceA.engine.sync();
            deviceB.engine.sync();

            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Evening Jog"));
            deviceB.time.advance(2_000);
            deviceB.engine.delete(EntityType.HABIT, HABIT);
            deviceB.engine.sync();

            SyncOutcome outcome = deviceA.engine.sync();

            // No register was contested — the tombstone landed on an unset lifecycle
            // register — so this is only visible by comparing the entity either side of
            // the page.
            assertThat(outcome.conflicts()).hasSize(1);
            Conflict conflict = outcome.conflicts().get(0);
            assertThat(conflict.kind()).isEqualTo(Conflict.Kind.HIDDEN_BY_DELETE);
            assertThat(conflict.field()).isNull();
            assertThat(conflict.lostLocalWrite()).isTrue();
            assertThat(conflict.winner().nodeId()).isEqualTo("device-b");
            assertThat(conflict.loser().nodeId()).isEqualTo("device-a");

            assertThat(deviceA.visible()).isFalse();
            assertThat(deviceA.name()).as("the write survives; only its visibility is lost").isEqualTo("Evening Jog");
        }

        @Test
        void staysSilentAboutTheUsersOwnDeleteComingBackFromTheServer() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));
            deviceA.engine.delete(EntityType.HABIT, HABIT);

            // The push, and then a pull that hands both ops straight back.
            deviceA.engine.sync();
            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(deviceA.visible()).isFalse();
            assertThat(outcome.conflicts()).as("the user deleted it on purpose").isEmpty();
        }

        /**
         * The failure that would make this feature worse than useless: an at-least-once
         * network manufacturing a conflict notice out of every successful round trip.
         */
        @Test
        void treatsARepeatedWriteAsIdempotenceRatherThanContention() throws Exception {
            FakeServer duplicating = new FakeServer().duplicateEveryPage();
            Device fresh = new Device("device-c", duplicating);
            duplicating.receiveFrom(
                    Change.upsert(
                            UUID.randomUUID(),
                            EntityType.HABIT,
                            HABIT,
                            new Hlc(1_700_000_001_000L, 0, "device-b"),
                            Map.of("name", FieldValue.of("Run"))));

            SyncOutcome first = fresh.engine.sync();
            SyncOutcome second = fresh.engine.sync();

            assertThat(first.conflicts()).isEmpty();
            assertThat(second.conflicts()).isEmpty();
            assertThat(first.conflictsObserved()).isZero();
        }

        @Test
        void saysNothingWhenAWriteLandsOnAFieldNobodyHadSet() throws Exception {
            server.receiveFrom(
                    Change.upsert(
                            UUID.randomUUID(),
                            EntityType.HABIT,
                            HABIT,
                            new Hlc(1_700_000_001_000L, 0, "device-b"),
                            Map.of("name", FieldValue.of("Run"))));

            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.conflicts()).isEmpty();
            assertThat(outcome.conflictsObserved()).isZero();
        }

        @Test
        void keepsCountingAfterItStopsListing() throws Exception {
            int overCap = SyncEngine.MAX_REPORTED_CONFLICTS + 20;
            for (int i = 0; i < overCap; i++) {
                UUID habit = UUID.randomUUID();
                deviceA.engine.upsert(EntityType.HABIT, habit, "name", FieldValue.of("local-" + i));
                // A later write from elsewhere, which will overwrite the local one.
                server.receiveFrom(
                        Change.upsert(
                                UUID.randomUUID(),
                                EntityType.HABIT,
                                habit,
                                new Hlc(deviceA.time.currentTimeMillis() + 60_000, i, "device-b"),
                                Map.of("name", FieldValue.of("remote-" + i))));
            }

            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.conflicts()).hasSize(SyncEngine.MAX_REPORTED_CONFLICTS);
            assertThat(outcome.conflictsObserved())
                    .as("a truncated list must not imply it listed everything")
                    .isEqualTo(overCap);
        }

        @Test
        void discardsReportsThatARequestedResyncHasMadeMeaningless() throws Exception {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From A"));
            deviceA.engine.sync();
            deviceB.time.advance(5_000);
            deviceB.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("From B"));
            deviceB.engine.sync();

            server.demandResync(1);
            SyncOutcome outcome = deviceA.engine.sync();

            assertThat(outcome.resynced()).isTrue();
            // A bootstrap replays the whole retained log, so every overwrite the account has
            // ever seen is re-derived here. Those verdicts are history, not news, and the
            // device has no way to tell which of them it already reported.
            assertThat(outcome.conflicts())
                    .as("a rebuild re-derives old verdicts; none of them are news")
                    .isEmpty();
            assertThat(outcome.conflictsObserved())
                    .as("and the count must not imply there were reports being withheld")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("clock handling")
    class Clocks {

        @Test
        void inboundTimestampsAreObservedSoLocalEditsStillWin() throws Exception {
            // A remote change stamped well ahead of this device's wall clock, but within
            // the drift tolerance.
            Hlc remote = new Hlc(deviceA.time.currentTimeMillis() + 60_000, 0, "device-b");
            server.receiveFrom(
                    Change.upsert(
                            UUID.randomUUID(), EntityType.HABIT, HABIT, remote, Map.of("name", FieldValue.of("Remote"))));

            deviceA.engine.sync();
            Change local = deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Local"));

            // Without observe(), this local edit would be stamped below the remote one and
            // the user's newest change would silently lose.
            assertThat(local.hlc().isAfter(remote)).isTrue();
            assertThat(deviceA.name()).isEqualTo("Local");
        }

        @Test
        void anAbsurdlySkewedPeerIsRejectedAndThePageIsLeftUnapplied() {
            Hlc fromTheFuture = new Hlc(deviceA.time.currentTimeMillis() + 86_400_000L, 0, "device-b");
            server.receiveFrom(
                    Change.upsert(
                            UUID.randomUUID(),
                            EntityType.HABIT,
                            HABIT,
                            fromTheFuture,
                            Map.of("name", FieldValue.of("Year 2038"))));

            assertThatThrownBy(deviceA.engine::sync).isInstanceOf(ClockDriftException.class);

            // Nothing applied, watermark untouched: the changes are re-offered once the
            // peer's clock is fixed, rather than being silently skipped.
            assertThat(deviceA.store.watermark()).isZero();
            assertThat(deviceA.store.allRecords()).isEmpty();
        }

        @Test
        void survivesProcessDeathWithoutReissuingATimestamp() {
            Change before = deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Before"));

            // The app is killed and the user's device clock is rolled back an hour.
            MutableTime restartedClock = new MutableTime();
            restartedClock.set(deviceA.time.currentTimeMillis() - 3_600_000L);
            SyncEngine restarted =
                    SyncEngine.forDevice("device-a", deviceA.store, server, restartedClock);

            Change after = restarted.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("After"));

            assertThat(after.hlc().isAfter(before.hlc()))
                    .as("a restarted device must not reissue or regress its own timestamps")
                    .isTrue();
            assertThat(deviceA.name()).isEqualTo("After");
        }

        @Test
        void refusesToAdoptAnotherDevicesPersistedClock() {
            deviceA.engine.upsert(EntityType.HABIT, HABIT, "name", FieldValue.of("Run"));

            assertThatThrownBy(
                            () -> SyncEngine.forDevice("device-b", deviceA.store, server, deviceA.time))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("belongs to node device-a");
        }

        @Test
        void startsFromZeroOnAFreshInstall() {
            SyncEngine fresh =
                    SyncEngine.forDevice("device-fresh", new InMemoryLocalStore(), server, new MutableTime());

            assertThat(fresh.nodeId()).isEqualTo("device-fresh");
            assertThat(fresh.pendingOpCount()).isZero();
        }
    }
}
