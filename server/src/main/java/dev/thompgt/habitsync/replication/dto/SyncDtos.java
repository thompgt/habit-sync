package dev.thompgt.habitsync.replication.dto;

import dev.thompgt.habitsync.sync.WireChange;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Request and response bodies for {@code /v1/sync}. */
public final class SyncDtos {

    private SyncDtos() {}

    /** Current wire protocol version. Bumped only for breaking changes. */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * A push, which also carries a pull so the common case is one round trip.
     *
     * @param sinceSeq the client's watermark: the highest {@code server_seq} it has
     *                 durably applied
     * @param ops      locally originated changes awaiting acknowledgement. May be empty,
     *                 which makes this a pure pull. Validated by {@code ChangeCodec} rather
     *                 than by bean-validation annotations, so that both ends of the
     *                 protocol accept and reject exactly the same payloads.
     */
    public record SyncRequest(
            @Min(0) long sinceSeq,
            @NotNull Integer protocolVersion,
            List<WireChange> ops) {

        public SyncRequest {
            ops = ops == null ? List.of() : List.copyOf(ops);
        }
    }

    /**
     * @param appliedOpIds  ops the server accepted, including ones it had already seen —
     *                      a replay is reported as applied so the client can clear them
     * @param changes       changes after {@code sinceSeq}, in sequence order
     * @param nextSeq       the watermark the client should store once it has durably
     *                      applied every change in this page
     * @param hasMore       more changes remain; the client should pull again immediately
     * @param resyncRequired the client's watermark is below the GC horizon, so the server
     *                      cannot prove it has seen the relevant tombstones. It must wipe
     *                      local state and bootstrap — after pushing pending ops.
     */
    public record SyncResponse(
            List<java.util.UUID> appliedOpIds,
            List<SyncChangeEnvelope> changes,
            long nextSeq,
            boolean hasMore,
            boolean resyncRequired,
            String resyncReason,
            long serverTimeMillis,
            int protocolVersion) {}

    /** A change paired with the sequence the server assigned it. */
    public record SyncChangeEnvelope(long serverSeq, WireChange change) {}
}
