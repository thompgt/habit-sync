package dev.thompgt.habitsync.sim;

import dev.thompgt.habitsync.sync.TimeSource;

/**
 * A wall clock the simulator owns outright: it advances when the simulation says so, by as
 * much as the simulation says, and never by itself.
 *
 * <p>{@link TimeSource#skewed(long)} is not usable here despite looking like exactly this.
 * It offsets {@link System#currentTimeMillis()}, so its readings depend on when the run
 * happened and how long each step took, and two runs of the same seed produce different HLC
 * timestamps — which is precisely the input the merge engine decides conflicts on. A
 * simulator whose conflict outcomes depend on machine speed cannot replay a failure.
 *
 * <p>Every replica gets its own instance, which is what makes skew expressible: they start
 * at the same instant and drift apart, so one device can genuinely be minutes ahead of
 * another, and of the server, for a stretch of the run.
 *
 * <h2>Why time moves at all</h2>
 *
 * A frozen clock would make every HLC on a device share a physical component and separate
 * only by the logical counter — a legitimate state, but a narrow one that never exercises
 * the physical-vs-logical comparison in {@link dev.thompgt.habitsync.sync.Hlc}. Letting time
 * advance in seeded jumps means both halves of the comparison do real work.
 */
public final class VirtualClock implements TimeSource {

    /**
     * An arbitrary fixed epoch for every run: 2024-01-01T00:00:00Z.
     *
     * <p>Fixed rather than "now" so that a failure report's HLC values are identical across
     * runs of the same seed, and comparable by eye between them.
     */
    public static final long SIMULATION_EPOCH_MILLIS = 1_704_067_200_000L;

    private long nowMillis;

    public VirtualClock(long startMillis) {
        this.nowMillis = startMillis;
    }

    /** A clock starting at the shared epoch, offset by {@code skewMillis}. */
    public static VirtualClock skewed(long skewMillis) {
        return new VirtualClock(SIMULATION_EPOCH_MILLIS + skewMillis);
    }

    @Override
    public long currentTimeMillis() {
        return nowMillis;
    }

    /** Moves the clock forward. */
    public void advance(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("Time does not run backwards here; got " + millis);
        }
        nowMillis += millis;
    }

    /**
     * Jumps the clock by an arbitrary amount, forwards or backwards — a device whose user
     * changed the date, or whose NTP client corrected a drift.
     *
     * <p>Backwards is the interesting direction and the reason this is separate from
     * {@link #advance}. An HLC must survive it: {@link dev.thompgt.habitsync.sync.HlcClock}
     * keeps its last reading and falls back to the logical counter rather than reissuing
     * timestamps it has already used. Nothing about that is exercised by a clock that only
     * ever moves forward.
     */
    public void jump(long millis) {
        nowMillis += millis;
    }

    @Override
    public String toString() {
        return "VirtualClock[" + nowMillis + "]";
    }
}
