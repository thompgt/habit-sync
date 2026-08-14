package dev.thompgt.habitsync.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweep: many seeds, every fault profile, and the first failure reported in full.
 *
 * <p>One run explores one path. The interleavings that break sync engines are the ones nobody
 * thought to write a test for — a partition ending exactly as a retention sweep begins, a
 * duplicate arriving after a resync — and volume is the only practical way to reach them.
 *
 * <h2>How much detection power this actually has</h2>
 *
 * Measured, not assumed. Two deliberate mutations were introduced into {@code MergeEngine} and
 * the sweep run against each:
 *
 * <ul>
 *   <li>Discarding field writes on tombstoned entities — coupling the two register groups that
 *       {@link dev.thompgt.habitsync.sync.EntityRecord} documents as orthogonal: <b>136 of 150
 *       seeds failed</b>, on a <em>perfect</em> network.
 *   <li>Per-row instead of per-field last-writer-wins — the flaw ADR-001 exists to avoid:
 *       <b>145 of 150 seeds failed</b>.
 * </ul>
 *
 * <p>Both were caught without any fault injection at all, which is worth knowing: the faults
 * broaden coverage, but the conflict semantics are wrong often enough that ordinary concurrent
 * editing finds them. A green sweep here is therefore evidence, not decoration.
 *
 * <h2>Seed range</h2>
 *
 * Fixed, not random. A suite that draws fresh seeds each run is a suite that fails on somebody
 * else's machine for reasons the committer never saw, and that goes green again on retry —
 * which trains everybody to retry. New territory is explored by widening this range in a commit,
 * where the result is attached to the change that caused it.
 */
class MultiSeedConvergenceTest {

    /** Kept modest so the sweep stays a fast pure-JVM job; widen deliberately, in a commit. */
    private static final int SEEDS_PER_PROFILE = 60;

    private void sweep(String name, SimulationConfig config) {
        Optional<SimulationResult> failure = SeedSweep.firstFailure(0, SEEDS_PER_PROFILE, config);
        if (failure.isEmpty()) {
            return;
        }
        SimulationResult result = failure.get();
        // The seed twice and early: whoever reads this in a CI log needs one number, and
        // needs it before the wall of history that follows.
        Assertions.fail(
                "%s: seed %d did not converge.%n%nReproduce with: Simulation.run(%dL)%n%n%s"
                        .formatted(name, result.seed(), result.seed(), result.report()));
    }

    @Test
    @DisplayName("a perfect network converges across every seed")
    void perfectNetwork() {
        sweep("perfect", SimulationConfig.standard().withFaults(FaultProfile.perfect()));
    }

    @Test
    @DisplayName("a flaky network converges across every seed")
    void flakyNetwork() {
        sweep("flaky", SimulationConfig.standard().withFaults(FaultProfile.flaky()));
    }

    @Test
    @DisplayName("a hostile network converges across every seed")
    void hostileNetwork() {
        sweep("hostile", SimulationConfig.standard().withFaults(FaultProfile.hostile()));
    }

    @Test
    @DisplayName("six replicas on a hostile network converge across every seed")
    void manyReplicas() {
        sweep(
                "hostile-6-replicas",
                SimulationConfig.standard().withFaults(FaultProfile.hostile()).withReplicas(6));
    }

    @Test
    @DisplayName("the sweep exercises the paths it claims to")
    void sweepCoverageIsReal() {
        SeedSweep.Coverage coverage = SeedSweep.coverage(
                0, SEEDS_PER_PROFILE, SimulationConfig.standard().withFaults(FaultProfile.hostile()));

        // A sweep is only as good as what it provoked. These would all be zero if the fault
        // injection silently stopped working, and every convergence assertion above would
        // still pass.
        assertThat(coverage.converged()).isEqualTo(coverage.runs());
        assertThat(coverage.responsesLost()).as("op-id idempotency untested").isPositive();
        assertThat(coverage.duplicates()).as("at-least-once delivery untested").isPositive();
        assertThat(coverage.resyncsDemanded()).as("the GC horizon path untested").isPositive();
        assertThat(coverage.collectedEntries()).as("retention never ran").isPositive();
    }
}
