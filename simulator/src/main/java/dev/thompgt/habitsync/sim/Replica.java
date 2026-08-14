package dev.thompgt.habitsync.sim;

import dev.thompgt.habitsync.sync.EntityKey;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.HlcClock;
import dev.thompgt.habitsync.sync.InMemoryLocalStore;
import dev.thompgt.habitsync.sync.MergeEngine;
import dev.thompgt.habitsync.sync.SyncEngine;
import dev.thompgt.habitsync.sync.Transport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One simulated device: its own clock, its own store, and the real {@link SyncEngine}.
 *
 * <p>Nothing here reimplements client behaviour. The engine is the production one, the store
 * is {@link InMemoryLocalStore} from {@code sync-core}'s main source set, and the merge is
 * the same {@link MergeEngine} the server runs. What the simulator supplies is only the
 * things a device gets from its platform — a clock and a network — which is exactly the seam
 * {@code sync-core} was built around.
 */
public final class Replica {

    private final String nodeId;
    private final VirtualClock clock;
    private final InMemoryLocalStore store = new InMemoryLocalStore();
    private final SyncEngine engine;

    private int syncAttempts;
    private int syncFailures;
    private int localEdits;

    public Replica(String nodeId, VirtualClock clock, Transport transport, Supplier<UUID> opIds) {
        if (nodeId.contains(":")) {
            // Hlc's compact encoding is colon-separated, so a nodeId containing one would
            // produce timestamps that parse back into something else entirely.
            throw new IllegalArgumentException("nodeId must not contain ':', got " + nodeId);
        }
        this.nodeId = nodeId;
        this.clock = clock;
        this.engine = new SyncEngine(
                new HlcClock(nodeId, clock),
                store,
                transport,
                new MergeEngine(),
                SyncEngine.DEFAULT_PUSH_BATCH_SIZE,
                SyncEngine.DEFAULT_MAX_PAGES_PER_SYNC,
                opIds);
    }

    public String nodeId() {
        return nodeId;
    }

    public VirtualClock clock() {
        return clock;
    }

    public SyncEngine engine() {
        return engine;
    }

    public InMemoryLocalStore store() {
        return store;
    }

    void countEdit() {
        localEdits++;
    }

    void countSyncAttempt(boolean failed) {
        syncAttempts++;
        if (failed) {
            syncFailures++;
        }
    }

    /**
     * @return this replica's view of the world, keyed for comparison — tombstoned entities
     *         included, because two replicas that agree on what is visible but disagree on
     *         what is deleted have not converged. The difference surfaces the moment one of
     *         them receives a late field write for the entity.
     */
    public Map<EntityKey, EntityRecord> state() {
        Map<EntityKey, EntityRecord> byKey = new LinkedHashMap<>();
        for (EntityRecord record : store.allRecords()) {
            byKey.put(record.key(), record);
        }
        return byKey;
    }

    /** @return true when this replica has nothing left to push. */
    public boolean settled() {
        return store.pendingOpCount() == 0;
    }

    public Stats stats() {
        return new Stats(nodeId, localEdits, syncAttempts, syncFailures, store.pendingOpCount(), store.watermark());
    }

    public record Stats(
            String nodeId, int localEdits, int syncAttempts, int syncFailures, int pendingOps, long watermark) {}
}
