package dev.thompgt.habitsync.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An in-process stand-in for {@code /v1/sync}: a replication log, an op-id set for
 * idempotency, and merged entity state, driven by the same {@link MergeEngine} the real
 * server uses.
 *
 * <p>It mirrors {@code SyncService}'s observable behaviour rather than reimplementing its
 * internals — sequence allocation in arrival order, replayed ops acknowledged without
 * being re-logged, pages bounded and cursored by {@code nextSeq}. Where the two could
 * drift, the server module's own Testcontainers tests are the authority; this exists so
 * the client engine can be exercised against protocol behaviour without a database.
 *
 * <p>The failure knobs are the point of having it: a transport that drops the next
 * response, a server that demands a resync, one that acknowledges ops it was never sent.
 * Those are the paths that are near-impossible to provoke against a real server and are
 * exactly where offline-first clients lose data.
 */
final class FakeServer implements Transport {

    private final MergeEngine mergeEngine = new MergeEngine();
    private final List<SequencedChange> log = new ArrayList<>();
    private final Set<UUID> committedOpIds = new HashSet<>();
    private final Map<EntityKey, EntityRecord> state = new LinkedHashMap<>();

    private int pageSize = 100;
    private int exchanges;

    // ------------------------------------------------------------- knobs

    private int failNextExchanges;
    private boolean retryableFailures = true;
    private int demandResyncTimes;
    private boolean duplicateEveryPage;
    private Set<UUID> phantomAcks = Set.of();
    private boolean freezeCursor;

    /** The next {@code n} exchanges throw instead of responding. */
    FakeServer failNext(int n) {
        this.failNextExchanges = n;
        return this;
    }

    FakeServer failNext(int n, boolean retryable) {
        this.failNextExchanges = n;
        this.retryableFailures = retryable;
        return this;
    }

    /** The next {@code n} exchanges answer "wipe your state and start over". */
    FakeServer demandResync(int n) {
        this.demandResyncTimes = n;
        return this;
    }

    /** Every page is delivered twice over, simulating an at-least-once network. */
    FakeServer duplicateEveryPage() {
        this.duplicateEveryPage = true;
        return this;
    }

    /** Acknowledge op ids the client never pushed — a confused or malicious server. */
    FakeServer alsoAcknowledge(Set<UUID> opIds) {
        this.phantomAcks = Set.copyOf(opIds);
        return this;
    }

    /** Report {@code hasMore} while returning a cursor that never advances. */
    FakeServer freezeCursor() {
        this.freezeCursor = true;
        return this;
    }

    FakeServer pageSize(int size) {
        this.pageSize = size;
        return this;
    }

    int exchangeCount() {
        return exchanges;
    }

    int logSize() {
        return log.size();
    }

    // -------------------------------------------------------- the protocol

    @Override
    public SyncResponse exchange(SyncRequest request) throws TransportException {
        exchanges++;

        if (failNextExchanges > 0) {
            failNextExchanges--;
            throw new TransportException("simulated network failure", null, retryableFailures);
        }

        Set<UUID> applied = commit(request.ops());
        applied.addAll(phantomAcks);

        if (demandResyncTimes > 0) {
            demandResyncTimes--;
            // Note the acks still ride along: a resync directive must not cost the client
            // the ops the server just committed, or it would push them all over again.
            return new SyncResponse(applied, List.of(), 0L, false, true, "watermarkBelowGcHorizon", 0L);
        }

        List<SequencedChange> page = new ArrayList<>();
        for (SequencedChange entry : log) {
            if (entry.serverSeq() > request.sinceSeq()) {
                page.add(entry);
                if (duplicateEveryPage) {
                    page.add(entry);
                }
                if (page.size() >= pageSize) {
                    break;
                }
            }
        }

        long highestServed = page.isEmpty()
                ? request.sinceSeq()
                : page.stream().mapToLong(SequencedChange::serverSeq).max().orElseThrow();
        boolean hasMore = !log.isEmpty() && log.get(log.size() - 1).serverSeq() > highestServed;

        long nextSeq = freezeCursor ? request.sinceSeq() : highestServed;
        return new SyncResponse(applied, page, nextSeq, hasMore || freezeCursor, false, null, 0L);
    }

    /** Appends fresh ops to the log and merges them; replays are acknowledged only. */
    private Set<UUID> commit(List<Change> ops) {
        Set<UUID> acknowledged = new HashSet<>();
        for (Change op : ops) {
            acknowledged.add(op.opId());
            if (!committedOpIds.add(op.opId())) {
                continue;
            }
            log.add(new SequencedChange(log.size() + 1L, op));
            state.put(op.key(), mergeEngine.merge(state.get(op.key()), op).state());
        }
        return acknowledged;
    }

    /** Injects a change as if another device had pushed it. */
    void receiveFrom(Change op) {
        commit(List.of(op));
    }

    EntityRecord serverState(EntityKey key) {
        return state.get(key);
    }
}
