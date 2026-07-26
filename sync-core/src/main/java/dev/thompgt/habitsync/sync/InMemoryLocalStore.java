package dev.thompgt.habitsync.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link LocalStore} held entirely in memory — the store the M6 convergence simulator
 * gives each of its virtual devices.
 *
 * <p>It lives in {@code main} rather than {@code test} on purpose. The simulator is a
 * deliverable of this project, not a test fixture of it, and it needs a store that
 * honours the interface's atomicity contract exactly. Anything weaker would make a
 * simulated convergence failure ambiguous: a bug in the engine, or a bug in the store
 * standing in for a real one?
 *
 * <p>Not for production use on a device — nothing here survives process death, which is
 * the one property {@link LocalStore} exists to guarantee.
 *
 * <p>Every method is synchronized, so a simulator may drive sync on one thread while
 * reading state from another. "Atomic" here means "under the instance monitor", which is
 * as strong as a single-process store can be and is the exact analogue of the
 * transaction a Room implementation would open.
 */
public final class InMemoryLocalStore implements LocalStore {

    private final Map<EntityKey, EntityRecord> entities = new LinkedHashMap<>();
    private final Map<UUID, Change> outbox = new LinkedHashMap<>();
    private long watermark;
    private Hlc lastClock;

    @Override
    public synchronized Optional<EntityRecord> load(EntityKey key) {
        return Optional.ofNullable(entities.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public synchronized void applyLocal(EntityRecord merged, Change op) {
        Objects.requireNonNull(merged, "merged");
        Objects.requireNonNull(op, "op");
        if (!merged.key().equals(op.key())) {
            throw new IllegalArgumentException(
                    "Record %s does not match op target %s".formatted(merged.key(), op.key()));
        }
        entities.put(merged.key(), merged);
        outbox.put(op.opId(), op);
        // Derived from the op rather than saved separately, so the clock cannot outlive
        // or trail the change it stamped. See LocalStore#lastClock.
        lastClock = Hlc.max(lastClock, op.hlc());
    }

    @Override
    public synchronized void applyRemote(Collection<EntityRecord> merged, long nextSeq, Hlc clock) {
        Objects.requireNonNull(merged, "merged");
        Objects.requireNonNull(clock, "clock");
        if (nextSeq < watermark) {
            // A watermark going backwards means changes already applied would be re-served
            // and, worse, that some caller has confused the cursor for a count.
            throw new IllegalArgumentException(
                    "Watermark must not go backwards: %d -> %d".formatted(watermark, nextSeq));
        }
        for (EntityRecord record : merged) {
            entities.put(record.key(), record);
        }
        watermark = nextSeq;
        lastClock = Hlc.max(lastClock, clock);
    }

    @Override
    public synchronized long watermark() {
        return watermark;
    }

    @Override
    public synchronized List<Change> pendingOps(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
        List<Change> page = new ArrayList<>(Math.min(limit, outbox.size()));
        for (Change op : outbox.values()) {
            if (page.size() == limit) {
                break;
            }
            page.add(op);
        }
        return List.copyOf(page);
    }

    @Override
    public synchronized int pendingOpCount() {
        return outbox.size();
    }

    @Override
    public synchronized void acknowledgeOps(Collection<UUID> opIds) {
        Objects.requireNonNull(opIds, "opIds").forEach(outbox::remove);
    }

    @Override
    public synchronized void resetForResync() {
        entities.clear();
        watermark = 0;
        // outbox and lastClock deliberately survive: un-pushed local work is not the
        // server's to discard, and the clock is this device's identity over time.
    }

    @Override
    public synchronized Optional<Hlc> lastClock() {
        return Optional.ofNullable(lastClock);
    }

    // ------------------------------------------------- inspection, for tests

    /** @return every entity this store holds, tombstoned ones included. */
    public synchronized List<EntityRecord> allRecords() {
        return List.copyOf(entities.values());
    }

    /** @return the entities the application would show the user. */
    public synchronized List<EntityRecord> visibleRecords() {
        return entities.values().stream().filter(EntityRecord::visible).toList();
    }

    /**
     * @return the ids of every op still awaiting acknowledgement — what a convergence
     *         check must find empty before it can call two devices settled.
     */
    public synchronized Set<UUID> pendingOpIds() {
        return Set.copyOf(outbox.keySet());
    }
}
