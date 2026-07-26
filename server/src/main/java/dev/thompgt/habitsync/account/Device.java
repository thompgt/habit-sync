package dev.thompgt.habitsync.account;

import java.time.Instant;
import java.util.UUID;

/**
 * A sync replica.
 *
 * @param lastSeenSeq highest {@code change_log.server_seq} this device has durably
 *                    applied; bounds tombstone GC (ADR-003)
 */
public record Device(UUID id, UUID userId, String displayName, long lastSeenSeq, Instant lastSeenAt) {

    /** This device's HLC node id. UUID text contains no {@code ':'}, which Hlc forbids. */
    public String nodeId() {
        return id.toString();
    }
}
