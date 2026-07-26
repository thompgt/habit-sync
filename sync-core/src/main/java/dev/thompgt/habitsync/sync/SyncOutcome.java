package dev.thompgt.habitsync.sync;

/**
 * What one call to {@link SyncEngine#sync()} accomplished.
 *
 * <p>Returned rather than logged, for the same reason {@link Resolution} is: sync-core
 * has no logger, and the three consumers want this in different forms — the UI shows
 * "last synced, 3 changes", the scheduler decides whether to run again immediately, and
 * tests assert on it.
 *
 * @param opsAcknowledged local ops the server confirmed, now cleared from the outbox
 * @param changesApplied  remote changes merged into local state, including ones fully
 *                        superseded — this counts what arrived, not what won
 * @param pagesFetched    round trips made
 * @param watermark       the watermark after this call
 * @param resynced        local state was wiped and rebuilt on the server's instruction
 * @param moreRemaining   work is still outstanding — either the server has more pages or
 *                        the outbox is not empty. Callers should run again promptly
 *                        rather than waiting for the next scheduled sync.
 */
public record SyncOutcome(
        int opsAcknowledged,
        int changesApplied,
        int pagesFetched,
        long watermark,
        boolean resynced,
        boolean moreRemaining) {

    /** Whether anything at all changed — useful for deciding to refresh a UI. */
    public boolean idle() {
        return opsAcknowledged == 0 && changesApplied == 0 && !resynced;
    }
}
