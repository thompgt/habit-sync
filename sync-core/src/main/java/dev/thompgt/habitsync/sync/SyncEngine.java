package dev.thompgt.habitsync.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The client half of the sync protocol: stamp local edits, push them, pull what the
 * device missed, merge it, and move the watermark — without ever losing a write to a
 * crash, a timeout, or a duplicate delivery.
 *
 * <p>Like {@link MergeEngine} this has no I/O of its own. Storage is
 * {@link LocalStore}, the network is {@link Transport}, and time is {@link TimeSource},
 * so the whole engine runs on a plain JVM. That is what makes the M6 convergence
 * simulator able to drive N replicas of the <em>real</em> client code across a partitioned
 * in-process network, instead of testing a model of it and hoping.
 *
 * <h2>Ordering, and why it is this way round</h2>
 *
 * Every round trip pushes before it pulls, and applies before it advances the watermark:
 *
 * <ol>
 *   <li><b>Push first.</b> The outbox is the only copy of a local edit the server has
 *       never seen. Pulling first would be harmless for correctness — merge does not care
 *       — but it delays the moment the user's work stops being single-homed on a phone.
 *   <li><b>Acknowledge on the server's word.</b> Ops are cleared from the outbox only
 *       when the server names them in {@code appliedOpIds}, which includes replays of ops
 *       it had already committed. A push whose response was lost is therefore resolved on
 *       the next attempt rather than retried forever.
 *   <li><b>Observe every inbound clock before merging.</b> Skipping this lets the device
 *       later stamp an edit below a timestamp it has already seen, so a fresh local edit
 *       silently loses to a stale remote one.
 *   <li><b>Apply, then advance.</b> Entities and the watermark move together, atomically
 *       ({@link LocalStore#applyRemote}). The failure this rules out is advancing past
 *       changes that were never stored, which is unrecoverable — the device will never
 *       ask for that range again.
 * </ol>
 *
 * <p>Nothing here retries or backs off. A failed round trip throws
 * {@link TransportException} with the outbox and watermark untouched, and <em>when</em> to
 * try again is a scheduling policy that belongs to the platform — WorkManager on Android,
 * a loop in the simulator.
 *
 * <p>Not thread-safe for concurrent {@link #sync()} calls; run one sync at a time per
 * device. Local mutations may be interleaved freely — they take the clock's lock, and the
 * worst case is an op landing in the outbox one round trip later than it might have.
 */
public final class SyncEngine {

    /** Ops per push. Matches the server's cap so a full batch is never rejected. */
    public static final int DEFAULT_PUSH_BATCH_SIZE = 500;

    /**
     * Round trips per {@link #sync()} call. Bounds the work one invocation can do so a
     * device returning from a long offline stretch yields to the scheduler — and battery,
     * and the user — instead of draining a backlog in one uninterruptible burst.
     */
    public static final int DEFAULT_MAX_PAGES_PER_SYNC = 20;

    private final HlcClock clock;
    private final LocalStore store;
    private final Transport transport;
    private final MergeEngine mergeEngine;
    private final int pushBatchSize;
    private final int maxPagesPerSync;

    public SyncEngine(HlcClock clock, LocalStore store, Transport transport) {
        this(clock, store, transport, new MergeEngine(), DEFAULT_PUSH_BATCH_SIZE, DEFAULT_MAX_PAGES_PER_SYNC);
    }

    public SyncEngine(
            HlcClock clock,
            LocalStore store,
            Transport transport,
            MergeEngine mergeEngine,
            int pushBatchSize,
            int maxPagesPerSync) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = Objects.requireNonNull(store, "store");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mergeEngine = Objects.requireNonNull(mergeEngine, "mergeEngine");
        if (pushBatchSize < 1) {
            throw new IllegalArgumentException("pushBatchSize must be >= 1, got " + pushBatchSize);
        }
        if (maxPagesPerSync < 1) {
            throw new IllegalArgumentException("maxPagesPerSync must be >= 1, got " + maxPagesPerSync);
        }
        this.pushBatchSize = pushBatchSize;
        this.maxPagesPerSync = maxPagesPerSync;
    }

    /**
     * Builds an engine for {@code nodeId}, restoring the device's clock from the store.
     *
     * <p>Use this at app startup in preference to constructing an {@link HlcClock}
     * directly — a clock that starts from zero after every process death reissues
     * timestamps it has already used. See {@link HlcClock#restored}.
     */
    public static SyncEngine forDevice(
            String nodeId, LocalStore store, Transport transport, TimeSource timeSource) {
        Objects.requireNonNull(store, "store");
        Optional<Hlc> previous = store.lastClock();
        HlcClock clock = previous
                .map(hlc -> HlcClock.restored(nodeId, timeSource, HlcClock.DEFAULT_MAX_DRIFT, hlc))
                .orElseGet(() -> new HlcClock(nodeId, timeSource));
        return new SyncEngine(clock, store, transport);
    }

    public String nodeId() {
        return clock.nodeId();
    }

    // ------------------------------------------------------------ local edits

    /**
     * Writes fields on an entity, creating it if this device has not seen it.
     *
     * <p>Applied locally at once — offline-first means the UI never waits on a network —
     * and queued for the next sync. The returned change is informational; the caller does
     * not need to hold on to it.
     */
    public Change upsert(EntityType type, UUID entityId, Map<String, FieldValue> fields) {
        if (fields.isEmpty()) {
            // An UPSERT with no fields would occupy a sequence number, cost a round trip
            // and change nothing anywhere. Catching it here beats debugging why the log
            // is full of no-ops.
            throw new IllegalArgumentException("An upsert must write at least one field");
        }
        return applyLocally(Change.upsert(UUID.randomUUID(), type, entityId, clock.tick(), fields));
    }

    /** Convenience for the common single-field write. */
    public Change upsert(EntityType type, UUID entityId, String field, FieldValue value) {
        return upsert(type, entityId, Map.of(field, value));
    }

    /** Tombstones an entity. It stops being {@link EntityRecord#visible()} immediately. */
    public Change delete(EntityType type, UUID entityId) {
        return applyLocally(Change.delete(UUID.randomUUID(), type, entityId, clock.tick()));
    }

    /** Clears a tombstone — the user's explicit undo. */
    public Change restore(EntityType type, UUID entityId) {
        return applyLocally(Change.restore(UUID.randomUUID(), type, entityId, clock.tick()));
    }

    private Change applyLocally(Change op) {
        EntityRecord current = store.load(op.key()).orElse(null);
        EntityRecord merged = mergeEngine.merge(current, op).state();
        // One atomic write: the entity the user can now see, and the op that will tell the
        // server about it. Splitting these is how an edit ends up visible on one device
        // and nowhere else, permanently.
        store.applyLocal(merged, op);
        return op;
    }

    /** @return the entity as this device currently sees it. */
    public Optional<EntityRecord> load(EntityType type, UUID entityId) {
        return store.load(new EntityKey(type, entityId));
    }

    /** @return local ops not yet confirmed by the server. */
    public int pendingOpCount() {
        return store.pendingOpCount();
    }

    // ----------------------------------------------------------------- sync

    /**
     * Runs a full sync: push the outbox, drain the server's backlog, merge everything.
     *
     * <p>Loops until the server reports no more pages and the outbox is empty, or until
     * {@code maxPagesPerSync} round trips have been made — whichever comes first. When it
     * stops early, {@link SyncOutcome#moreRemaining()} says so.
     *
     * @throws TransportException  the round trip failed; nothing was acknowledged or
     *                             advanced beyond what earlier pages already committed
     * @throws ClockDriftException a peer's clock is implausibly far ahead. The page is
     *                             discarded whole and the watermark is left alone, so the
     *                             changes are re-offered once the situation is resolved —
     *                             absorbing the timestamp instead would let one broken
     *                             device starve every honest write on the account.
     */
    public SyncOutcome sync() throws TransportException {
        int acknowledged = 0;
        int applied = 0;
        int pages = 0;
        boolean resynced = false;

        while (pages < maxPagesPerSync) {
            long watermarkBefore = store.watermark();
            List<Change> ops = store.pendingOps(pushBatchSize);

            SyncResponse response = transport.exchange(new SyncRequest(watermarkBefore, ops));
            pages++;

            int ackedNow = acknowledge(response, ops);
            acknowledged += ackedNow;

            if (response.resyncRequired()) {
                if (resynced) {
                    // Twice in one sync means resetting did not satisfy the server, and
                    // looping would wipe local state repeatedly while making no progress.
                    // Not retryable: this needs a human, not a backoff.
                    throw new TransportException(
                            "Server demanded a resync again immediately after one completed (reason: "
                                    + response.resyncReason() + ")",
                            null,
                            false);
                }
                // Entity state goes; the outbox stays. Those ops are this device's own
                // un-pushed work, and the server's retention policy is no reason to bin it.
                store.resetForResync();
                resynced = true;
                continue;
            }

            int appliedNow = applyPage(response, watermarkBefore);
            applied += appliedNow;

            boolean outboxDrained = store.pendingOpCount() == 0;
            if (!response.hasMore() && outboxDrained) {
                return new SyncOutcome(acknowledged, applied, pages, store.watermark(), resynced, false);
            }

            // Progress guard. Without it, a server that reports hasMore but returns an
            // unchanging page, or one that never acknowledges a poison op, spins this loop
            // until maxPagesPerSync burning battery and bandwidth on every sync forever.
            if (ackedNow == 0 && appliedNow == 0) {
                return new SyncOutcome(acknowledged, applied, pages, store.watermark(), resynced, true);
            }
        }

        return new SyncOutcome(acknowledged, applied, pages, store.watermark(), resynced, true);
    }

    private int acknowledge(SyncResponse response, List<Change> pushed) {
        if (response.appliedOpIds().isEmpty()) {
            return 0;
        }
        // Only clear ops this device actually sent in this request. A server naming an id
        // we never pushed is confused or talking about another device; dropping our outbox
        // on its say-so would lose the edit outright.
        List<UUID> ours = pushed.stream()
                .map(Change::opId)
                .filter(response.appliedOpIds()::contains)
                .toList();
        if (!ours.isEmpty()) {
            store.acknowledgeOps(ours);
        }
        return ours.size();
    }

    /**
     * Merges one page and commits it with its watermark.
     *
     * <p>Changes are folded per entity in memory first, so a page touching one entity
     * fifty times costs one load and one write rather than fifty of each. Order within the
     * page is irrelevant — merge is commutative — but folding in arrival order keeps the
     * intermediate states meaningful when stepping through a debugger.
     */
    private int applyPage(SyncResponse response, long watermarkBefore) throws TransportException {
        if (response.changes().isEmpty()) {
            return 0;
        }
        if (response.nextSeq() <= watermarkBefore) {
            // The cursor must advance whenever a page is non-empty, or the next request
            // asks for the same range and the loop never terminates.
            throw new TransportException(
                    "Server returned %d changes but nextSeq %d did not advance past %d"
                            .formatted(response.changes().size(), response.nextSeq(), watermarkBefore),
                    null,
                    false);
        }

        Map<EntityKey, EntityRecord> working = new LinkedHashMap<>();
        for (SequencedChange sequenced : response.changes()) {
            Change change = sequenced.change();

            // Before the merge, always. This is what makes the clock causal rather than a
            // timestamp generator, and it throws rather than absorbing an absurd skew.
            clock.observe(change.hlc());

            EntityKey key = change.key();
            // Not computeIfAbsent: a not-yet-known entity maps to null, which
            // computeIfAbsent refuses to store, so it would reload on every change.
            if (!working.containsKey(key)) {
                working.put(key, store.load(key).orElse(null));
            }
            working.put(key, mergeEngine.merge(working.get(key), change).state());
        }

        List<EntityRecord> merged = new ArrayList<>(working.values());
        store.applyRemote(merged, response.nextSeq(), clock.peek());
        return response.changes().size();
    }
}
