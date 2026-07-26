package dev.thompgt.habitsync.sync;

import java.util.Objects;

/**
 * A change paired with the sequence number the server assigned it.
 *
 * <p>The sequence is the server's, not the originating device's: it is the position in
 * the per-user replication log, allocated under a row lock at commit time (ADR-002). It
 * has nothing to do with the change's {@link Hlc} and must never be used to order a
 * merge — two changes can arrive in sequence order 7, 8 while their HLCs say the
 * opposite, and the HLC is the one that decides who wins.
 *
 * <p>Its only job is to be a resumable cursor: "give me everything after 42".
 *
 * @param serverSeq position in the user's replication log, strictly increasing
 * @param change    the change itself
 */
public record SequencedChange(long serverSeq, Change change) {

    public SequencedChange {
        if (serverSeq < 1) {
            throw new IllegalArgumentException("serverSeq must be >= 1, got " + serverSeq);
        }
        Objects.requireNonNull(change, "change");
    }
}
