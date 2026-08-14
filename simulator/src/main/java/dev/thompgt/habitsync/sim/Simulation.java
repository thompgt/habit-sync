package dev.thompgt.habitsync.sim;

import dev.thompgt.habitsync.sync.ClockDriftException;
import dev.thompgt.habitsync.sync.HlcClock;
import dev.thompgt.habitsync.sync.SyncOutcome;
import dev.thompgt.habitsync.sync.TransportException;
import java.util.ArrayList;
import java.util.List;

/**
 * One deterministic convergence run: N replicas, a hostile network, a seeded scheduler, and
 * the question at the end.
 *
 * <p>Single-threaded on purpose. Real concurrency would make runs unreproducible, and it would
 * buy nothing: the orderings that break a sync engine are orderings of <em>messages</em>, not
 * of instructions, and a seeded scheduler can produce any of those on one thread. Threads would
 * trade the simulator's only real asset — replayability — for a class of bug the engine's own
 * locking already handles.
 *
 * <h2>The two phases</h2>
 *
 * <ol>
 *   <li><b>Chaos.</b> Steps are drawn from the seed: a replica writes, a replica syncs, the
 *       network partitions or heals, a clock jumps, the server collects its log. Nothing is
 *       expected to be consistent during this phase and nothing is asserted.
 *   <li><b>Quiesce.</b> Faults off, partitions healed, and every replica syncs in turn until
 *       nothing is pending anywhere. Only then is convergence a meaningful question — before
 *       that, disagreement just means the news has not arrived.
 * </ol>
 */
public final class Simulation {

    /**
     * How far a replica's clock may stray from the server's, as a fraction of the drift the
     * server tolerates.
     *
     * <p>A quarter, so that even two replicas skewed to opposite extremes are half a window
     * apart and every op they produce is acceptable to the server and to each other.
     *
     * <p>Skew beyond the window is deliberately not part of a convergence run. The server
     * refuses those ops (ADR-001) and the client re-queues them unchanged, so they are never
     * applied anywhere and the run could not converge — not because merge is wrong, but
     * because the system is correctly refusing to accept a poisoned timestamp. That path is
     * worth testing and is tested separately; mixing it in here would only produce a
     * convergence failure that means the opposite of what it appears to.
     */
    private static final long SKEW_WINDOW_MILLIS = HlcClock.DEFAULT_MAX_DRIFT.toMillis() / 4;

    /** Wall-clock advance per step. Enough that HLCs separate physically, not only logically. */
    private static final long STEP_MILLIS = 250;

    private final SimulationConfig config;
    private final SimRandom random;
    private final VirtualClock serverClock;
    private final SimulatedServer server;
    private final Network network;
    private final List<Replica> replicas = new ArrayList<>();
    private final Workload workload;
    private final List<String> history = new ArrayList<>();

    public Simulation(long seed, SimulationConfig config) {
        this.config = config;
        this.random = new SimRandom(seed);
        this.serverClock = VirtualClock.skewed(0);
        this.server = new SimulatedServer(serverClock, random::nextUuid);
        this.network = new Network(server, random, config.faults());
        this.workload = new Workload(random);

        for (int i = 0; i < config.replicaCount(); i++) {
            String nodeId = "device-" + i;
            long skew = random.nextLong(-SKEW_WINDOW_MILLIS / 2, SKEW_WINDOW_MILLIS / 2);
            replicas.add(new Replica(
                    nodeId, VirtualClock.skewed(skew), network.transportFor(nodeId), random::nextUuid));
        }
    }

    /** Convenience for the common case. */
    public static SimulationResult run(long seed) {
        return new Simulation(seed, SimulationConfig.standard()).run();
    }

    public SimulationResult run() {
        for (int step = 0; step < config.steps(); step++) {
            tick();
            if (random.chance(config.editProbability())) {
                record(workload.act(pickReplica()));
            } else {
                attemptSync(pickReplica());
            }
            injectFaults();
        }

        int rounds = quiesce();
        boolean quiesced = rounds <= config.maxQuiesceRounds();
        List<String> divergences = ConvergenceOracle.compare(server, replicas);

        return new SimulationResult(
                random.seed(),
                divergences.isEmpty(),
                quiesced,
                rounds,
                divergences,
                history,
                server.stats(),
                network.stats(),
                replicas.stream().map(Replica::stats).toList());
    }

    /** The server this run drove. Exposed so a test can plant a divergence and check it is caught. */
    public SimulatedServer server() {
        return server;
    }

    /** The replicas this run drove, in creation order. */
    public List<Replica> replicas() {
        return List.copyOf(replicas);
    }

    // ------------------------------------------------------------ the chaos

    private void tick() {
        serverClock.advance(STEP_MILLIS);
        for (Replica replica : replicas) {
            replica.clock().advance(STEP_MILLIS);
        }
    }

    private Replica pickReplica() {
        return random.pick(replicas);
    }

    private void attemptSync(Replica replica) {
        try {
            SyncOutcome outcome = replica.engine().sync();
            replica.countSyncAttempt(false);
            record("%s syncs: %d acked, %d applied, watermark %d%s"
                    .formatted(
                            replica.nodeId(),
                            outcome.opsAcknowledged(),
                            outcome.changesApplied(),
                            outcome.watermark(),
                            outcome.resynced() ? " (resynced)" : ""));
        } catch (TransportException e) {
            replica.countSyncAttempt(true);
            record("%s sync failed: %s".formatted(replica.nodeId(), e.getMessage()));
        } catch (ClockDriftException e) {
            // Reachable in principle even inside the skew window, if a replica's clock is
            // corrected downwards after it has already stamped ops. The op stays in the
            // outbox and is re-offered, which is the documented behaviour.
            replica.countSyncAttempt(true);
            record("%s sync rejected for drift: %s".formatted(replica.nodeId(), e.getMessage()));
        }
    }

    private void injectFaults() {
        FaultProfile faults = config.faults();

        for (Replica replica : replicas) {
            String nodeId = replica.nodeId();
            if (network.isPartitioned(nodeId)) {
                if (random.chance(faults.partitionEnd())) {
                    network.heal(nodeId);
                    record(nodeId + " reconnects");
                }
            } else if (random.chance(faults.partitionStart())) {
                network.partition(nodeId);
                record(nodeId + " drops off the network");
            }

            if (random.chance(faults.clockJump())) {
                long before = replica.clock().currentTimeMillis();
                replica.clock().jump(random.nextLong(-SKEW_WINDOW_MILLIS, SKEW_WINDOW_MILLIS));
                clampToSkewWindow(replica);
                record("%s clock %d -> %d".formatted(nodeId, before, replica.clock().currentTimeMillis()));
            }
        }

        if (random.chance(faults.retentionSweep())) {
            // Keeps a short tail rather than collecting outright, which is what leaves some
            // replicas servable incrementally and strands others below the horizon -- the
            // mixed case, where a bug in the horizon check shows up as one replica silently
            // missing changes rather than as everybody resyncing.
            int collected = server.collectAllButNewest(random.nextInt(0, 5));
            if (collected > 0) {
                record("server collects %d log entries, oldest retained now above seq %d"
                        .formatted(collected, server.headSequence() - server.logSize()));
            }
        }
    }

    /**
     * Pulls a replica's clock back inside the window the server tolerates.
     *
     * <p>Without this, jumps accumulate: each one is bounded, and a seeded walk of bounded
     * steps still leaves the window eventually, at which point the server refuses everything
     * that replica writes and the run fails for a reason that has nothing to do with
     * convergence. Clamping keeps skew adversarial and legal at the same time.
     */
    private void clampToSkewWindow(Replica replica) {
        long serverNow = serverClock.currentTimeMillis();
        long now = replica.clock().currentTimeMillis();
        long clamped = Math.max(serverNow - SKEW_WINDOW_MILLIS, Math.min(serverNow + SKEW_WINDOW_MILLIS, now));
        if (clamped != now) {
            replica.clock().jump(clamped - now);
        }
    }

    // ---------------------------------------------------------- the settling

    /**
     * Runs the network clean until nothing is outstanding anywhere.
     *
     * <p>Settled means two things at once, and both are needed: no replica has un-pushed ops,
     * and every replica's watermark has reached the server's head. The first alone would
     * declare a replica finished that has pushed everything and pulled nothing; the second
     * alone would miss a replica holding local work it has never managed to send.
     *
     * @return the number of rounds used; greater than the budget means it never settled
     */
    private int quiesce() {
        network.healAll();
        network.setFaults(FaultProfile.perfect());
        record("-- quiesce: network healed, faults off --");

        int round = 0;
        while (round < config.maxQuiesceRounds()) {
            round++;
            tick();
            for (Replica replica : replicas) {
                attemptSync(replica);
            }
            if (allSettled()) {
                return round;
            }
        }
        return config.maxQuiesceRounds() + 1;
    }

    private boolean allSettled() {
        long head = server.headSequence();
        for (Replica replica : replicas) {
            if (!replica.settled() || replica.store().watermark() != head) {
                return false;
            }
        }
        return true;
    }

    private void record(String event) {
        history.add(event);
    }
}
