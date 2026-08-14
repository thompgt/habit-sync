package dev.thompgt.habitsync.sim;

/**
 * How hostile the simulated network is, as independent per-exchange probabilities.
 *
 * @param requestLost      the request never reaches the server. The cheap failure: nothing
 *                         was committed, so a retry is trivially correct.
 * @param responseLost     the server commits the push and the reply is lost on the way
 *                         back. <b>The one that matters.</b> The client cannot distinguish
 *                         it from {@code requestLost} and must retry, so the server sees the
 *                         same ops twice and only op-id idempotency prevents them being
 *                         applied twice. Almost every hand-rolled sync layer loses data
 *                         here, and it is nearly impossible to provoke against a real
 *                         server.
 * @param duplicated       the request is delivered twice — an at-least-once network, or a
 *                         retry that raced its own original.
 * @param partitionStart   chance per step that a connected replica drops off the network.
 * @param partitionEnd     chance per step that a partitioned replica comes back.
 * @param clockJump        chance per step that a replica's wall clock is corrected or
 *                         mangled, forwards or backwards.
 * @param retentionSweep   chance per step that the server collects its log, stranding any
 *                         replica whose watermark falls below the horizon and forcing the
 *                         resync path.
 */
public record FaultProfile(
        double requestLost,
        double responseLost,
        double duplicated,
        double partitionStart,
        double partitionEnd,
        double clockJump,
        double retentionSweep) {

    public FaultProfile {
        check("requestLost", requestLost);
        check("responseLost", responseLost);
        check("duplicated", duplicated);
        check("partitionStart", partitionStart);
        check("partitionEnd", partitionEnd);
        check("clockJump", clockJump);
        check("retentionSweep", retentionSweep);
    }

    private static void check(String name, double p) {
        if (!(p >= 0.0 && p <= 1.0)) {
            throw new IllegalArgumentException(name + " must be a probability in [0,1], got " + p);
        }
    }

    /** A perfect network. Useful as a control: if this diverges, the fault knobs are innocent. */
    public static FaultProfile perfect() {
        return new FaultProfile(0, 0, 0, 0, 0, 0, 0);
    }

    /** Occasional trouble — roughly what a phone on a train experiences. */
    public static FaultProfile flaky() {
        return new FaultProfile(0.05, 0.05, 0.05, 0.05, 0.30, 0.02, 0.01);
    }

    /**
     * Aggressively hostile. Not realistic, and not meant to be: the point of a simulator is
     * to reach in a hundred seconds the states a real deployment reaches once a year.
     */
    public static FaultProfile hostile() {
        return new FaultProfile(0.15, 0.15, 0.20, 0.15, 0.25, 0.10, 0.05);
    }
}
