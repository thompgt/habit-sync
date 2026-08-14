package dev.thompgt.habitsync.sim;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Every non-deterministic decision the simulator makes, funnelled through one seed.
 *
 * <p>This is the property the whole simulator rests on: a run is identified by a
 * {@code long}, and reporting that {@code long} is sufficient to reproduce a failure
 * exactly. A single unseeded call anywhere — a {@code HashSet} iteration order that leaks
 * into a decision, a {@code UUID.randomUUID()}, a wall-clock read — quietly destroys it, and
 * destroys it in the least visible way possible: the suite still passes, failures still get
 * reported with a seed, and the seed no longer reproduces them.
 *
 * <p>{@link Random} rather than a better modern generator on purpose. Its algorithm is
 * specified exactly by the JDK, so a seed reproduces the same stream on any JVM and any
 * platform — which is what makes a seed from a CI log usable on a laptop. The statistical
 * quality of an LCG is irrelevant here; nothing is being sampled, only scheduled.
 *
 * <p>Not thread-safe, and deliberately so: the simulator is single-threaded, because a
 * concurrent scheduler could not be replayed from a seed at all.
 */
public final class SimRandom {

    private final long seed;
    private final Random random;

    public SimRandom(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    /** The seed this generator was created with — quote it in any failure report. */
    public long seed() {
        return seed;
    }

    /** @return a uniform value in {@code [0, bound)}. */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /** @return a uniform value in {@code [origin, bound)}. */
    public int nextInt(int origin, int bound) {
        if (bound <= origin) {
            throw new IllegalArgumentException("bound %d must exceed origin %d".formatted(bound, origin));
        }
        return origin + random.nextInt(bound - origin);
    }

    /** @return a uniform value in {@code [origin, bound]}. */
    public long nextLong(long origin, long bound) {
        if (bound < origin) {
            throw new IllegalArgumentException("bound %d must be >= origin %d".formatted(bound, origin));
        }
        return origin + (long) (random.nextDouble() * (bound - origin + 1));
    }

    /** @return true with probability {@code p}, where {@code p} is in {@code [0, 1]}. */
    public boolean chance(double p) {
        return random.nextDouble() < p;
    }

    /** @return a uniformly chosen element. */
    public <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cannot pick from an empty list");
        }
        return items.get(random.nextInt(items.size()));
    }

    /**
     * @return a UUID drawn from the seeded stream.
     *
     * <p>Not {@link UUID#randomUUID()}, which reads the system entropy source and is the
     * single most common way a "deterministic" simulator turns out not to be. These are not
     * cryptographically random and do not need to be — they are names, and their only
     * requirement is that the stream not repeat one within a run.
     */
    public UUID nextUuid() {
        return new UUID(random.nextLong(), random.nextLong());
    }
}
