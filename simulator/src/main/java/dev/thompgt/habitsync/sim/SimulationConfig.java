package dev.thompgt.habitsync.sim;

/**
 * The shape of one simulation run.
 *
 * @param replicaCount    how many devices share the account. Three or more matters: with two,
 *                        every change either originates locally or arrives from the one peer,
 *                        and the case where a replica learns about a write third-hand — after
 *                        it has already been merged, re-logged and paged by someone else —
 *                        never occurs.
 * @param steps           how many scheduler steps to run before quiescing.
 * @param editProbability chance that a step is a local edit rather than a sync attempt. Above
 *                        roughly 0.5 the replicas write faster than they can drain, which is
 *                        realistic for an offline device but leaves most of the run untested
 *                        because everything converges in the quiesce phase instead.
 * @param faults          how badly the network behaves during the run.
 * @param maxQuiesceRounds how many settling rounds to allow before declaring the run stuck.
 *                        A bound rather than a loop-until-done, so a genuine livelock fails
 *                        the run in seconds instead of hanging CI.
 */
public record SimulationConfig(
        int replicaCount,
        int steps,
        double editProbability,
        FaultProfile faults,
        int maxQuiesceRounds) {

    public SimulationConfig {
        if (replicaCount < 2) {
            throw new IllegalArgumentException("A convergence test needs at least 2 replicas");
        }
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be >= 1, got " + steps);
        }
        if (!(editProbability >= 0.0 && editProbability <= 1.0)) {
            throw new IllegalArgumentException("editProbability must be in [0,1], got " + editProbability);
        }
        if (maxQuiesceRounds < 1) {
            throw new IllegalArgumentException("maxQuiesceRounds must be >= 1, got " + maxQuiesceRounds);
        }
    }

    /** The default shape: three devices, a flaky network, a few hundred steps. */
    public static SimulationConfig standard() {
        return new SimulationConfig(3, 400, 0.45, FaultProfile.flaky(), 200);
    }

    public SimulationConfig withFaults(FaultProfile replacement) {
        return new SimulationConfig(replicaCount, steps, editProbability, replacement, maxQuiesceRounds);
    }

    public SimulationConfig withReplicas(int count) {
        return new SimulationConfig(count, steps, editProbability, faults, maxQuiesceRounds);
    }

    public SimulationConfig withSteps(int count) {
        return new SimulationConfig(replicaCount, count, editProbability, faults, maxQuiesceRounds);
    }
}
