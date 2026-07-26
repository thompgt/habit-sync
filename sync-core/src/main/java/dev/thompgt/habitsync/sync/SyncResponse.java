package dev.thompgt.habitsync.sync;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The server's answer to a {@link SyncRequest}.
 *
 * @param appliedOpIds  ops the server has committed — including ones it had already seen
 *                      before this request. A replay is reported as applied rather than
 *                      rejected, which is what lets a client whose push timed out after
 *                      the server committed clear its outbox instead of retrying forever.
 * @param changes       changes after the request's watermark, in sequence order
 * @param nextSeq       the watermark to store <em>once every change in this page is
 *                      durable locally</em>. Not the server's head: it is the last
 *                      sequence actually included, so storing it can never skip a change
 *                      the page omitted.
 * @param hasMore       more changes remain beyond this page; pull again immediately
 * @param resyncRequired the device's watermark is below the server's GC horizon, so the
 *                      server cannot prove the device has seen the relevant tombstones.
 *                      Local state must be wiped and rebuilt from sequence 0 (ADR-003).
 * @param resyncReason  machine-readable reason for {@code resyncRequired}, for logs and
 *                      metrics; {@code null} otherwise
 * @param serverTimeMillis the server's wall clock, for drift diagnostics. Deliberately
 *                      <em>not</em> fed into the HLC: trusting a server timestamp would
 *                      reintroduce the central-sequencer dependency HLCs exist to avoid.
 */
public record SyncResponse(
        Set<UUID> appliedOpIds,
        List<SequencedChange> changes,
        long nextSeq,
        boolean hasMore,
        boolean resyncRequired,
        String resyncReason,
        long serverTimeMillis) {

    public SyncResponse {
        appliedOpIds = Set.copyOf(Objects.requireNonNull(appliedOpIds, "appliedOpIds"));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (nextSeq < 0) {
            throw new IllegalArgumentException("nextSeq must be >= 0, got " + nextSeq);
        }
        if (resyncRequired && !changes.isEmpty()) {
            // A resync directive means "throw away what you have"; shipping changes
            // alongside it invites a client to apply them onto state it is about to wipe.
            throw new IllegalArgumentException("A resync response must not carry changes");
        }
    }

    /** A response carrying nothing new. */
    public static SyncResponse empty(long seq) {
        return new SyncResponse(Set.of(), List.of(), seq, false, false, null, 0L);
    }
}
