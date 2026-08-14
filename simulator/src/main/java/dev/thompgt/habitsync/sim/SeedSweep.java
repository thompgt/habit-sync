package dev.thompgt.habitsync.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs many seeds and reports the first that fails.
 *
 * <p>A single simulation run explores one path. Convergence bugs live in the paths nobody
 * thought to write down — a partition that ends exactly as a retention sweep starts, a
 * duplicate delivered after a resync — and the only practical way to reach them is volume.
 * Each seed is one sample of the space, which is why a run's identity has to be a single
 * number: a failure is only useful if the number that produced it is enough to get it back.
 *
 * <p>Stops at the first failure rather than collecting them all. The failures in a sweep are
 * rarely independent — one bug usually fails many seeds — and a report listing forty of them
 * is harder to act on than the smallest one.
 */
public final class SeedSweep {

    private SeedSweep() {}

    /**
     * @param from   first seed, inclusive
     * @param count  how many consecutive seeds to run
     * @return the first failing run, or empty if all of them converged
     */
    public static Optional<SimulationResult> firstFailure(long from, int count, SimulationConfig config) {
        for (long seed = from; seed < from + count; seed++) {
            SimulationResult result = new Simulation(seed, config).run();
            if (!result.passed()) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    /** Aggregate counters over a sweep, so a green run can still be judged for coverage. */
    public static Coverage coverage(long from, int count, SimulationConfig config) {
        List<SimulationResult> results = new ArrayList<>();
        for (long seed = from; seed < from + count; seed++) {
            results.add(new Simulation(seed, config).run());
        }

        int converged = 0;
        int responsesLost = 0;
        int resyncs = 0;
        int collected = 0;
        int duplicates = 0;
        for (SimulationResult result : results) {
            if (result.passed()) {
                converged++;
            }
            responsesLost += result.networkStats().responsesLost();
            resyncs += result.serverStats().resyncsDemanded();
            collected += result.serverStats().collectedEntries();
            duplicates += result.networkStats().duplicates();
        }
        return new Coverage(count, converged, responsesLost, resyncs, collected, duplicates);
    }

    public record Coverage(
            int runs, int converged, int responsesLost, int resyncsDemanded, int collectedEntries, int duplicates) {}

    /**
     * Entry point for running a sweep outside JUnit.
     *
     * <p>Usage: {@code SeedSweep <fromSeed> <count>}. Exits non-zero on the first failure and
     * prints its full report, seed first.
     */
    public static void main(String[] args) {
        long from = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 100;

        Optional<SimulationResult> failure = firstFailure(from, count, SimulationConfig.standard());
        if (failure.isPresent()) {
            System.out.println(failure.get().report());
            System.out.println("Reproduce with: Simulation.run(" + failure.get().seed() + "L)");
            System.exit(1);
        }
        System.out.println("All " + count + " seeds from " + from + " converged.");
        System.out.println(coverage(from, count, SimulationConfig.standard()));
    }
}
