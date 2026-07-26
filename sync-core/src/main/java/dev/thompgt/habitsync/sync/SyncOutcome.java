package dev.thompgt.habitsync.sync;

import java.util.List;
import java.util.Objects;

/**
 * What one call to {@link SyncEngine#sync()} accomplished.
 *
 * <p>Returned rather than logged, for the same reason {@link Resolution} is: sync-core
 * has no logger, and the three consumers want this in different forms — the UI shows
 * "last synced, 3 changes", the scheduler decides whether to run again immediately, and
 * tests assert on it.
 *
 * @param opsAcknowledged   local ops the server confirmed, now cleared from the outbox
 * @param changesApplied    remote changes merged into local state, including ones fully
 *                          superseded — this counts what arrived, not what won
 * @param pagesFetched      round trips made
 * @param watermark         the watermark after this call
 * @param resynced          local state was wiped and rebuilt on the server's instruction
 * @param moreRemaining     work is still outstanding — either the server has more pages or
 *                          the outbox is not empty. Callers should run again promptly
 *                          rather than waiting for the next scheduled sync.
 * @param conflicts         work this sync discarded or hid, capped at
 *                          {@link SyncEngine#MAX_REPORTED_CONFLICTS}. ADR-001 and ADR-003
 *                          both accept losing data on the condition that the loss is
 *                          surfaced; this is what a client shows to honour that.
 * @param conflictsObserved how many conflicts actually occurred, which exceeds
 *                          {@code conflicts.size()} once the cap bites. Kept as a count so
 *                          a UI can say "and 40 more" rather than implying it listed
 *                          everything.
 */
public record SyncOutcome(
        int opsAcknowledged,
        int changesApplied,
        int pagesFetched,
        long watermark,
        boolean resynced,
        boolean moreRemaining,
        List<Conflict> conflicts,
        int conflictsObserved) {

    public SyncOutcome {
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
    }

    /** Whether anything at all changed — useful for deciding to refresh a UI. */
    public boolean idle() {
        return opsAcknowledged == 0 && changesApplied == 0 && !resynced;
    }

    /**
     * Conflicts that cost the user work they did on <em>this</em> device.
     *
     * <p>The subset worth an unprompted notice. Everything else — a write another replica
     * lost to a value this device already held — belongs in a debug screen, where
     * {@link #conflicts()} serves it.
     */
    public List<Conflict> lostLocalWrites() {
        return conflicts.stream().filter(Conflict::lostLocalWrite).toList();
    }
}
