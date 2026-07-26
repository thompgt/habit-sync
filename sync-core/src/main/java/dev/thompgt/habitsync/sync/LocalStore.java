package dev.thompgt.habitsync.sync;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The device's durable state, behind the narrowest interface the engine can work with:
 * merged entity records, an outbox of un-acknowledged local ops, and the pull watermark.
 *
 * <p>On Android this is Room. In the convergence simulator it is
 * {@link InMemoryLocalStore}. The engine cannot tell the difference, which is the point.
 *
 * <h2>Atomicity is the contract</h2>
 *
 * Three methods here are required to be <b>atomic and durable</b> —
 * {@link #applyLocal}, {@link #applyRemote}, and {@link #resetForResync}. This is not
 * incidental; each one pairs a state write with a bookkeeping write, and a crash between
 * the two halves corrupts the device in a way no amount of retrying repairs:
 *
 * <ul>
 *   <li>{@link #applyLocal} writes the entity <em>and</em> enqueues the op. Entity
 *       without op means the user's edit shows on screen and never reaches the server —
 *       silent data loss, discovered weeks later.
 *   <li>{@link #applyRemote} writes the entities <em>and</em> advances the watermark.
 *       Advancing first means a crash skips those changes permanently, because the
 *       device will never ask for that range again.
 * </ul>
 *
 * <p>The reverse orderings are all survivable: an op without its entity re-merges
 * harmlessly, and a watermark behind the applied state re-pulls changes that merge to a
 * no-op. Merge is idempotent precisely so that the safe failure mode is the one that
 * costs bandwidth rather than data. Implementations that cannot offer a transaction must
 * at minimum order their writes so that the surviving prefix is the recoverable one.
 *
 * <p>Implementations must be safe to call from a background sync worker while the UI
 * thread reads.
 */
public interface LocalStore {

    /** @return the merged state of {@code key}, or empty if this device has never seen it. */
    Optional<EntityRecord> load(EntityKey key);

    /**
     * Records a locally originated change: writes the merged entity and enqueues the op
     * for push, atomically.
     *
     * @param merged the entity state after merging {@code op} — the engine has already
     *               done the merge, so the store just persists it
     * @param op     the change to hand to the server on the next sync
     */
    void applyLocal(EntityRecord merged, Change op);

    /**
     * Records a batch pulled from the server: writes the merged entities and advances the
     * watermark to {@code nextSeq}, atomically.
     *
     * <p>Called once per page. Passing the whole page rather than one record at a time is
     * what lets an implementation wrap it in a single transaction.
     *
     * @param merged  entity states after merging the page, at most one entry per entity
     * @param nextSeq the new watermark; never lower than the current one
     * @param clock   this device's clock reading after observing every change in the page;
     *                persisted in the same transaction, see {@link #lastClock()}
     */
    void applyRemote(Collection<EntityRecord> merged, long nextSeq, Hlc clock);

    /** @return the highest {@code serverSeq} durably applied; 0 if nothing ever has been. */
    long watermark();

    /**
     * @param limit maximum ops to return
     * @return un-acknowledged local ops, oldest first. Order is a courtesy to the server's
     *         logs — merge does not depend on it.
     */
    List<Change> pendingOps(int limit);

    /** @return the total number of un-acknowledged local ops, for backlog reporting. */
    int pendingOpCount();

    /** Removes ops the server has confirmed committed. Unknown ids are ignored. */
    void acknowledgeOps(Collection<UUID> opIds);

    /**
     * Wipes merged entity state and resets the watermark to 0, <b>keeping the outbox</b>.
     *
     * <p>Keeping it is deliberate. A resync means the server has garbage-collected log
     * entries this device never read; it says nothing about the device's own un-pushed
     * edits, and discarding a week of offline work to recover from a server-side
     * retention decision would be an unforced data loss. Those ops are re-pushed, and the
     * server merges them against whatever it holds — the ordinary path.
     */
    void resetForResync();

    /**
     * @return the highest clock reading this store has persisted, or empty on a fresh
     *         install. Implementations derive it from the writes they already accept —
     *         {@code op.hlc()} in {@link #applyLocal} and the {@code clock} argument to
     *         {@link #applyRemote} — rather than from a separate save call.
     *
     * <p>There is deliberately no {@code saveClock} method, because a separate call could
     * land on the other side of a crash from the op it stamped. That is the one failure
     * this design cannot tolerate: reusing an HLC for two different local changes gives
     * two writes an identical timestamp, and merge's strictly-greater rule then lets
     * replicas that see them in different orders pick different winners. Duplicate
     * timestamps are the one way to break convergence from the client side, so the clock
     * is persisted by the same atomic write as the change that used it, or not at all.
     *
     * <p>Note {@link #resetForResync()} must <b>not</b> clear this. The clock is this
     * device's monotonic history; a resync discards replicated state, not identity.
     */
    Optional<Hlc> lastClock();
}
