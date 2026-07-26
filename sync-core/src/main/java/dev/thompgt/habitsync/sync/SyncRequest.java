package dev.thompgt.habitsync.sync;

import java.util.List;
import java.util.Objects;

/**
 * One round trip's worth of work: everything this device wants to send, and where it
 * wants to resume reading from.
 *
 * <p>Push and pull are combined deliberately. The common case on a phone is "I made two
 * edits and want whatever I missed", and splitting that into two requests doubles the
 * radio wake-ups for no benefit. A request with no {@code ops} is a pure pull.
 *
 * <p>This is sync-core's transport-independent shape, not the wire format. Mapping it to
 * JSON — stringly-typed enums, HLCs in their compact encoding, a protocol version — is
 * the {@link Transport} implementation's job, because that encoding is a compatibility
 * surface that belongs with the code that speaks HTTP.
 *
 * @param sinceSeq the highest {@code serverSeq} this device has <em>durably</em> applied
 * @param ops      locally originated changes awaiting acknowledgement; may be empty
 */
public record SyncRequest(long sinceSeq, List<Change> ops) {

    public SyncRequest {
        if (sinceSeq < 0) {
            throw new IllegalArgumentException("sinceSeq must be >= 0, got " + sinceSeq);
        }
        ops = List.copyOf(Objects.requireNonNull(ops, "ops"));
    }

    /** A request that pulls from {@code sinceSeq} without pushing anything. */
    public static SyncRequest pullOnly(long sinceSeq) {
        return new SyncRequest(sinceSeq, List.of());
    }
}
