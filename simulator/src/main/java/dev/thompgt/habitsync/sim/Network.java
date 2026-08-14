package dev.thompgt.habitsync.sim;

import dev.thompgt.habitsync.sync.SyncRequest;
import dev.thompgt.habitsync.sync.SyncResponse;
import dev.thompgt.habitsync.sync.Transport;
import dev.thompgt.habitsync.sync.TransportException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The wire between the replicas and the server, and every way it is allowed to misbehave.
 *
 * <p>Each replica gets a {@link Transport} from {@link #transportFor}, so from the engine's
 * point of view this is indistinguishable from a real network — which is the point. The
 * engine under test is the production one, not a variant that knows it is being simulated.
 *
 * <h2>The fault that earns the whole simulator</h2>
 *
 * {@link FaultProfile#responseLost()} delivers the request, lets the server commit it, and
 * <em>then</em> throws. The client learns nothing and must retry, so the server sees the same
 * ops a second time. If op-id idempotency is wrong in any way, this duplicates writes; if the
 * client cleared its outbox optimistically instead of on the server's acknowledgement, this
 * loses them. Both bugs are invisible on a healthy network and both destroy user data on a
 * flaky one.
 *
 * <p>Note what is <em>not</em> modelled: latency, and reordering of one replica's own
 * requests. {@link Transport#exchange} is synchronous request-response and the simulation is
 * single-threaded, so a replica has at most one request in flight and there is nothing to
 * reorder within it. Reordering <em>between</em> replicas is real and is modelled — by the
 * scheduler choosing who acts next, which is the only ordering the merge engine can observe.
 */
public final class Network {

    private final SimulatedServer server;
    private final SimRandom random;
    private final FaultProfile faults;
    private final Set<String> partitioned = new LinkedHashSet<>();

    private int delivered;
    private int requestsLost;
    private int responsesLost;
    private int duplicates;
    private int blockedByPartition;

    public Network(SimulatedServer server, SimRandom random, FaultProfile faults) {
        this.server = server;
        this.random = random;
        this.faults = faults;
    }

    /** @return a transport for {@code nodeId}, subject to this network's faults. */
    public Transport transportFor(String nodeId) {
        return request -> deliver(nodeId, request);
    }

    private SyncResponse deliver(String nodeId, SyncRequest request) throws TransportException {
        if (partitioned.contains(nodeId)) {
            blockedByPartition++;
            throw new TransportException("partitioned: " + nodeId, null, true);
        }
        if (random.chance(faults.requestLost())) {
            requestsLost++;
            throw new TransportException("request lost in flight", null, true);
        }

        SyncResponse response = server.exchange(request);
        delivered++;

        if (random.chance(faults.duplicated())) {
            // Delivered a second time, and the second response is the one returned. Both are
            // valid answers to the same question; taking the later one is what a network that
            // reordered the duplicate ahead of the original would produce.
            duplicates++;
            response = server.exchange(request);
        }

        if (random.chance(faults.responseLost())) {
            responsesLost++;
            throw new TransportException("response lost after the server committed", null, true);
        }
        return response;
    }

    // ------------------------------------------------------------ partitions

    public boolean isPartitioned(String nodeId) {
        return partitioned.contains(nodeId);
    }

    public void partition(String nodeId) {
        partitioned.add(nodeId);
    }

    public void heal(String nodeId) {
        partitioned.remove(nodeId);
    }

    /**
     * Reconnects everything.
     *
     * <p>Called before the convergence check. Convergence is a claim about what happens
     * <em>once communication is restored</em> — a replica that is still cut off has not
     * failed to converge, it has simply not been told anything yet, and asserting over it
     * would be asserting that the network is reliable rather than that the engine is correct.
     */
    public void healAll() {
        partitioned.clear();
    }

    public Stats stats() {
        return new Stats(delivered, requestsLost, responsesLost, duplicates, blockedByPartition);
    }

    /**
     * What the network actually did. Worth printing on success as well as failure: a run
     * reporting zero lost responses proves nothing about idempotency however green it is.
     */
    public record Stats(
            int delivered, int requestsLost, int responsesLost, int duplicates, int blockedByPartition) {}
}
