package dev.thompgt.habitsync.sync;

/**
 * Wall-clock time, injected rather than called statically.
 *
 * <p>This exists so the convergence simulator can give each virtual device its own
 * skewed, frozen, or fast-forwarded clock. Clock skew is one of the two conditions
 * (the other being network reordering) that separates a sync engine that happens to
 * work from one that is actually correct, and it cannot be tested against
 * {@code System.currentTimeMillis()}.
 */
@FunctionalInterface
public interface TimeSource {

    /** @return milliseconds since the Unix epoch. */
    long currentTimeMillis();

    /** The real system clock. */
    static TimeSource system() {
        return System::currentTimeMillis;
    }

    /** A clock offset from the system clock by a fixed amount — for simulating skew. */
    static TimeSource skewed(long offsetMillis) {
        return () -> System.currentTimeMillis() + offsetMillis;
    }
}
