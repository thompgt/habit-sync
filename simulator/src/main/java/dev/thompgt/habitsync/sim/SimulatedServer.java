package dev.thompgt.habitsync.sim;

import dev.thompgt.habitsync.sync.Change;
import dev.thompgt.habitsync.sync.ClockDriftException;
import dev.thompgt.habitsync.sync.EntityKey;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.Hlc;
import dev.thompgt.habitsync.sync.HlcClock;
import dev.thompgt.habitsync.sync.MergeEngine;
import dev.thompgt.habitsync.sync.SequencedChange;
import dev.thompgt.habitsync.sync.SyncRequest;
import dev.thompgt.habitsync.sync.SyncResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One account's server, in memory: a replication log, an op-id set for idempotency, merged
 * entity state, and the retention collector — driven by the real {@link MergeEngine}.
 *
 * <p>It <b>mirrors</b> the production {@code SyncService} rather than reusing it. Reuse
 * would mean Postgres, Testcontainers and Docker, and would put a database's startup cost in
 * front of every one of the thousands of round trips a single simulation run makes. What is
 * reused is the part that decides outcomes: the merge engine is the same class the server
 * and every client run, so a convergence failure here is a convergence failure there.
 *
 * <p>The cost of mirroring is honest and worth stating: this class could drift from
 * {@code SyncService}. Where the two disagree, {@code SyncService} and its Testcontainers
 * tests are the authority — the behaviours mirrored here are the ones with observable
 * protocol consequences, and each is noted against the rule it implements.
 *
 * <h2>What is deliberately simplified</h2>
 *
 * Sequence allocation is a counter rather than a locked counter row. The lock in ADR-002
 * exists to make sequence order equal <em>commit</em> order under concurrency; this server
 * is single-threaded and processes one exchange at a time, so allocation order is commit
 * order by construction. Simulating the lock would be simulating a property this
 * environment cannot violate.
 */
public final class SimulatedServer {

    /** Matches the production {@code SyncService.MAX_PAGE_SIZE} rounding. */
    public static final int DEFAULT_PAGE_SIZE = 500;

    private final MergeEngine mergeEngine = new MergeEngine();

    /**
     * The log, kept as a contiguous suffix. ADR-003's horizon check is only sound if
     * collection trims the front and never punches a hole, so this list is only ever
     * truncated from index 0.
     */
    private final List<SequencedChange> log = new ArrayList<>();

    private final Set<UUID> committedOpIds = new LinkedHashSet<>();
    private final Map<EntityKey, EntityRecord> state = new LinkedHashMap<>();
    private final VirtualClock clock;
    private final Supplier<UUID> opIds;
    private final int pageSize;
    private final long maxDriftMillis;

    private long nextSeq = 1;

    private int exchanges;
    private int bootstraps;
    private int resyncsDemanded;
    private int driftRejections;
    private int collectedEntries;

    public SimulatedServer(VirtualClock clock, Supplier<UUID> opIds) {
        this(clock, opIds, DEFAULT_PAGE_SIZE, HlcClock.DEFAULT_MAX_DRIFT.toMillis());
    }

    public SimulatedServer(VirtualClock clock, Supplier<UUID> opIds, int pageSize, long maxDriftMillis) {
        this.clock = clock;
        this.opIds = opIds;
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1, got " + pageSize);
        }
        this.pageSize = pageSize;
        this.maxDriftMillis = maxDriftMillis;
    }

    // ------------------------------------------------------------- protocol

    /** Handles one push-and-pull round trip. */
    public SyncResponse exchange(SyncRequest request) {
        exchanges++;

        Set<UUID> applied = push(request.ops());

        if (request.sinceSeq() == 0) {
            return bootstrap(applied);
        }
        return pull(request.sinceSeq(), applied);
    }

    /**
     * Commits fresh ops and acknowledges replays.
     *
     * <p>All-or-nothing on clock drift, exactly as the real server is: the check runs over
     * the whole batch before anything is committed, so a rejected push leaves the server
     * untouched and the client's outbox intact.
     */
    private Set<UUID> push(List<Change> ops) {
        for (Change op : ops) {
            if (op.hlc().physicalMillis() > clock.currentTimeMillis() + maxDriftMillis) {
                driftRejections++;
                throw new ClockDriftException(op.hlc(), clock.currentTimeMillis(), maxDriftMillis);
            }
        }

        Set<UUID> acknowledged = new LinkedHashSet<>();
        for (Change op : ops) {
            // Acknowledged whether or not it is fresh. A replay of an op the server already
            // holds must clear the client's outbox, or a push whose response was lost is
            // retried forever.
            acknowledged.add(op.opId());
            if (!committedOpIds.add(op.opId())) {
                continue;
            }
            log.add(new SequencedChange(nextSeq++, op));
            state.put(op.key(), mergeEngine.merge(state.get(op.key()), op).state());
        }
        return acknowledged;
    }

    private SyncResponse pull(long sinceSeq, Set<UUID> applied) {
        long currentSeq = nextSeq - 1;

        // ADR-003's horizon check. An empty log is the same question with no oldest entry to
        // ask it of: either the account was never written to, or collection took the log
        // whole — and then a client below the head has missed everything.
        Optional<Long> oldestRetained = log.isEmpty() ? Optional.empty() : Optional.of(log.get(0).serverSeq());
        boolean missedCollected =
                oldestRetained.map(oldest -> oldest > sinceSeq + 1).orElse(sinceSeq < currentSeq);

        if (missedCollected) {
            resyncsDemanded++;
            return new SyncResponse(
                    applied, List.of(), currentSeq, false, true, "watermarkBelowGcHorizon", clock.currentTimeMillis());
        }

        List<SequencedChange> page = new ArrayList<>();
        for (SequencedChange entry : log) {
            if (entry.serverSeq() > sinceSeq) {
                page.add(entry);
                if (page.size() == pageSize) {
                    break;
                }
            }
        }

        // nextSeq is the last sequence actually served, never the head: reporting the head
        // would advance the client past changes this page omitted.
        long served = page.isEmpty() ? sinceSeq : page.get(page.size() - 1).serverSeq();
        boolean hasMore = !log.isEmpty() && log.get(log.size() - 1).serverSeq() > served;

        return new SyncResponse(applied, page, served, hasMore, false, null, clock.currentTimeMillis());
    }

    /**
     * Serves a device at sequence 0 from current entity state rather than by replaying the
     * log, which is what makes the log collectable at all.
     *
     * <p>One synthesised change per field, because each field register carries its own clock
     * and a single flattened UPSERT per entity would hand the device wrong provenance for
     * every field it did not most recently write.
     */
    private SyncResponse bootstrap(Set<UUID> applied) {
        bootstraps++;
        long snapshotSeq = nextSeq - 1;

        List<SequencedChange> changes = new ArrayList<>();
        for (EntityRecord record : state.values()) {
            for (Map.Entry<String, dev.thompgt.habitsync.sync.FieldValue> field : record.fields().entrySet()) {
                changes.add(new SequencedChange(
                        snapshotSeq,
                        Change.upsert(
                                opIds.get(),
                                record.type(),
                                record.id(),
                                record.fieldClocks().get(field.getKey()),
                                Map.of(field.getKey(), field.getValue()))));
            }
            Hlc lifecycle = record.lifecycleClock();
            if (lifecycle != null) {
                changes.add(new SequencedChange(
                        snapshotSeq,
                        record.deleted()
                                ? Change.delete(opIds.get(), record.type(), record.id(), lifecycle)
                                : Change.restore(opIds.get(), record.type(), record.id(), lifecycle)));
            }
        }

        // Sent whole rather than paged, matching the server: a snapshot is bounded by the
        // account's live entities rather than by its history.
        return new SyncResponse(applied, changes, snapshotSeq, false, false, null, clock.currentTimeMillis());
    }

    // ------------------------------------------------------------ retention

    /**
     * Collects the log prefix at or below {@code horizonSeq}, as the nightly sweep does.
     *
     * <p>A prefix, never a range. The horizon check above asks whether the oldest surviving
     * sequence is above the client's watermark, which answers "has this client missed
     * anything" only when what survives is contiguous.
     *
     * @return how many entries were removed
     */
    public int collectUpTo(long horizonSeq) {
        int removed = 0;
        while (!log.isEmpty() && log.get(0).serverSeq() <= horizonSeq) {
            log.remove(0);
            removed++;
        }
        collectedEntries += removed;
        return removed;
    }

    /** Collects everything but the newest {@code keep} entries — the simulator's fault knob. */
    public int collectAllButNewest(int keep) {
        if (log.size() <= keep) {
            return 0;
        }
        return collectUpTo(log.get(log.size() - keep - 1).serverSeq());
    }

    // ----------------------------------------------------------- inspection

    /** @return the server's merged state, tombstones included — one half of the oracle. */
    public Map<EntityKey, EntityRecord> state() {
        return Map.copyOf(state);
    }

    public long headSequence() {
        return nextSeq - 1;
    }

    public int logSize() {
        return log.size();
    }

    public Stats stats() {
        return new Stats(exchanges, bootstraps, resyncsDemanded, driftRejections, collectedEntries, log.size());
    }

    /** Counters worth reporting alongside a run, so a "passing" run that did nothing shows up. */
    public record Stats(
            int exchanges,
            int bootstraps,
            int resyncsDemanded,
            int driftRejections,
            int collectedEntries,
            int logSize) {}
}
