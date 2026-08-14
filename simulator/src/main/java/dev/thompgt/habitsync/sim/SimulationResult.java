package dev.thompgt.habitsync.sim;

import java.util.List;

/**
 * What one run did, and whether it converged.
 *
 * @param seed        the run's identity. Everything below is reproducible from it alone.
 * @param converged   true when every replica and the server agreed at the end.
 * @param quiesced    whether the settling phase finished within its round budget. A run that
 *                    did not quiesce is a failure in its own right and is reported separately
 *                    from divergence, because the two have completely different causes: a
 *                    livelock in the sync loop versus a disagreement about state.
 * @param divergences one line per disagreement, empty when converged.
 * @param history     every action taken, in order.
 */
public record SimulationResult(
        long seed,
        boolean converged,
        boolean quiesced,
        int quiesceRounds,
        List<String> divergences,
        List<String> history,
        SimulatedServer.Stats serverStats,
        Network.Stats networkStats,
        List<Replica.Stats> replicaStats) {

    public SimulationResult {
        divergences = List.copyOf(divergences);
        history = List.copyOf(history);
        replicaStats = List.copyOf(replicaStats);
    }

    public boolean passed() {
        return converged && quiesced;
    }

    /**
     * A report fit to paste into a bug.
     *
     * <p>The seed comes first and the history last, because the seed is the only part a reader
     * needs in order to reproduce everything else. The counters in between exist to catch the
     * quiet failure mode of any fault-injection suite: a run that passes because nothing
     * interesting happened. Zero lost responses means idempotency was never tested, however
     * green the result.
     */
    public String report() {
        StringBuilder out = new StringBuilder();
        out.append("Simulation seed ").append(seed).append(passed() ? " PASSED" : " FAILED").append('\n');
        if (!quiesced) {
            out.append("  did not settle within ").append(quiesceRounds).append(" rounds\n");
        }
        out.append("  server: ").append(serverStats).append('\n');
        out.append("  network: ").append(networkStats).append('\n');
        for (Replica.Stats stats : replicaStats) {
            out.append("  replica: ").append(stats).append('\n');
        }
        if (!divergences.isEmpty()) {
            out.append("  divergences (").append(divergences.size()).append("):\n");
            for (String divergence : divergences) {
                out.append("    ").append(divergence).append('\n');
            }
        }
        if (!passed()) {
            out.append("  history:\n");
            for (int i = 0; i < history.size(); i++) {
                out.append("    ").append(i + 1).append(". ").append(history.get(i)).append('\n');
            }
        }
        return out.toString();
    }
}
