package dev.thompgt.habitsync.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HlcClockTest {

    /** A clock the test drives by hand, so ordering assertions are not races. */
    private static final class MutableClock implements TimeSource {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void set(long value) {
            this.millis = value;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }
    }

    @Test
    void tickTracksTheWallClockWhenItAdvances() {
        MutableClock time = new MutableClock(1_000);
        HlcClock clock = new HlcClock("node-a", time);

        assertThat(clock.tick()).isEqualTo(new Hlc(1_000, 0, "node-a"));

        time.set(1_005);
        assertThat(clock.tick()).isEqualTo(new Hlc(1_005, 0, "node-a"));
    }

    @Test
    @DisplayName("many events within one millisecond stay strictly ordered")
    void advancesLogicallyWithinAMillisecond() {
        HlcClock clock = new HlcClock("node-a", new MutableClock(1_000));

        assertThat(clock.tick()).isEqualTo(new Hlc(1_000, 0, "node-a"));
        assertThat(clock.tick()).isEqualTo(new Hlc(1_000, 1, "node-a"));
        assertThat(clock.tick()).isEqualTo(new Hlc(1_000, 2, "node-a"));
    }

    @Test
    @DisplayName("a wall clock that jumps backwards must not produce a lower timestamp")
    void isMonotonicAcrossBackwardsClockJumps() {
        MutableClock time = new MutableClock(5_000);
        HlcClock clock = new HlcClock("node-a", time);
        Hlc before = clock.tick();

        // NTP correction, timezone bug, user changing the date by hand.
        time.set(1_000);
        Hlc after = clock.tick();

        assertThat(after).isGreaterThan(before);
        assertThat(after.physicalMillis()).isEqualTo(5_000);
        assertThat(after.logical()).isEqualTo(1);
    }

    @Test
    void tickIsStrictlyMonotonicOverManyCalls() {
        MutableClock time = new MutableClock(1_000);
        HlcClock clock = new HlcClock("node-a", time);

        List<Hlc> stamps = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            if (i % 7 == 0) {
                time.set(time.currentTimeMillis() + 1);
            }
            if (i % 53 == 0) {
                time.set(Math.max(0, time.currentTimeMillis() - 3)); // jitter backwards
            }
            stamps.add(clock.tick());
        }

        for (int i = 1; i < stamps.size(); i++) {
            assertThat(stamps.get(i))
                    .as("stamp %d must exceed stamp %d", i, i - 1)
                    .isGreaterThan(stamps.get(i - 1));
        }
    }

    @Test
    @DisplayName("observing a peer makes subsequent local events order after it — causality")
    void observeCarriesCausality() {
        MutableClock time = new MutableClock(1_000);
        HlcClock clock = new HlcClock("node-a", time);

        Hlc remote = new Hlc(4_000, 9, "node-b");
        clock.observe(remote);
        Hlc local = clock.tick();

        assertThat(local).isGreaterThan(remote);
        assertThat(local.physicalMillis()).isEqualTo(4_000);
    }

    @Test
    void observeStepsPastTheHigherLogicalCounterWhenPhysicalTimesMatch() {
        MutableClock time = new MutableClock(1_000);
        HlcClock clock = new HlcClock("node-a", time);
        clock.tick(); // (1000, 0)

        clock.observe(new Hlc(1_000, 5, "node-b"));

        assertThat(clock.peek()).isEqualTo(new Hlc(1_000, 6, "node-a"));
    }

    @Test
    void observeResetsTheCounterWhenTheLocalWallClockLeadsBoth() {
        MutableClock time = new MutableClock(9_000);
        HlcClock clock = new HlcClock("node-a", time);

        clock.observe(new Hlc(1_000, 5, "node-b"));

        assertThat(clock.peek()).isEqualTo(new Hlc(9_000, 0, "node-a"));
    }

    @Test
    void observeIsIdempotentForAlreadySeenTimestamps() {
        MutableClock time = new MutableClock(1_000);
        HlcClock clock = new HlcClock("node-a", time);
        Hlc remote = new Hlc(2_000, 3, "node-b");

        clock.observe(remote);
        Hlc afterFirst = clock.peek();
        clock.observe(remote);
        Hlc afterSecond = clock.peek();

        // Re-observing must not rewind, and must keep the clock strictly above the peer.
        assertThat(afterSecond).isGreaterThanOrEqualTo(afterFirst);
        assertThat(afterSecond).isGreaterThan(remote);
    }

    @Test
    @DisplayName("a peer whose clock is set to 2038 is rejected, not absorbed")
    void rejectsExcessiveForwardDrift() {
        MutableClock time = new MutableClock(1_000_000);
        HlcClock clock = new HlcClock("node-a", time, Duration.ofMinutes(5));

        Hlc wayAhead = new Hlc(1_000_000 + Duration.ofDays(365).toMillis(), 0, "broken-node");

        assertThatThrownBy(() -> clock.observe(wayAhead))
                .isInstanceOf(ClockDriftException.class)
                .hasMessageContaining("broken-node");

        // Crucially, the clock is unpoisoned: honest writes can still win afterwards.
        assertThat(clock.tick().physicalMillis()).isEqualTo(1_000_000);
    }

    @Test
    void acceptsDriftWithinTolerance() {
        MutableClock time = new MutableClock(1_000_000);
        HlcClock clock = new HlcClock("node-a", time, Duration.ofMinutes(5));

        Hlc slightlyAhead = new Hlc(1_000_000 + Duration.ofMinutes(4).toMillis(), 0, "node-b");

        assertThat(clock.observe(slightlyAhead)).isGreaterThan(slightlyAhead);
    }

    @Test
    void acceptsArbitrarilyStaleTimestamps() {
        // Backward drift needs no guard: an old timestamp simply loses.
        MutableClock time = new MutableClock(1_000_000);
        HlcClock clock = new HlcClock("node-a", time);

        assertThat(clock.observe(new Hlc(1, 0, "node-b")).physicalMillis()).isEqualTo(1_000_000);
    }

    @Test
    void rejectsInvalidNodeIdAtConstructionRatherThanFirstUse() {
        assertThatThrownBy(() -> new HlcClock("bad:id", TimeSource.system()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain");
    }

    @Test
    void isSafeUnderConcurrentTicks() throws InterruptedException {
        HlcClock clock = new HlcClock("node-a", new MutableClock(1_000));
        int threads = 8;
        int perThread = 2_000;
        List<Hlc> collected = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    collected.add(clock.tick());
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        // Every stamp must be unique; a duplicate means two events are unordered.
        assertThat(collected).hasSize(threads * perThread);
        assertThat(new java.util.HashSet<>(collected)).hasSameSizeAs(collected);
    }
}
