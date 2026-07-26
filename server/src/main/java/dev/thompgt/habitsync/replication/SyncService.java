package dev.thompgt.habitsync.replication;

import dev.thompgt.habitsync.account.AccountRepository;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncChangeEnvelope;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncRequest;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncResponse;
import dev.thompgt.habitsync.security.AuthenticatedUser;
import dev.thompgt.habitsync.sync.Change;
import dev.thompgt.habitsync.sync.ChangeCodec;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import dev.thompgt.habitsync.sync.Hlc;
import dev.thompgt.habitsync.sync.MergeEngine;
import dev.thompgt.habitsync.sync.Resolution;
import dev.thompgt.habitsync.sync.WireChange;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sync endpoint's engine: accept a client's ops, merge them, append them to the
 * replication log, and return everything the client has not yet seen.
 *
 * <p>The merge itself is delegated to {@code sync-core}'s {@link MergeEngine} — the same
 * class the Android client runs. Neither side has its own copy of the conflict rules, so
 * they cannot drift apart.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** Bounded so one enormous pull cannot exhaust server memory or a mobile connection. */
    public static final int MAX_PAGE_SIZE = 500;

    /** Bounded so one client cannot submit an unbounded batch and hold the user's lock. */
    public static final int MAX_OPS_PER_PUSH = 500;

    private final ChangeLogRepository changeLog;
    private final EntityRepository entities;
    private final AccountRepository accounts;
    private final MergeEngine mergeEngine;
    private final Clock clock;

    public SyncService(
            ChangeLogRepository changeLog,
            EntityRepository entities,
            AccountRepository accounts,
            MergeEngine mergeEngine,
            Clock clock) {
        this.changeLog = changeLog;
        this.entities = entities;
        this.accounts = accounts;
        this.mergeEngine = mergeEngine;
        this.clock = clock;
    }

    /**
     * Processes a combined push-and-pull.
     *
     * <p>READ_COMMITTED is sufficient and deliberate: correctness here comes from the
     * explicit row lock in {@link ChangeLogRepository#allocateSequenceRange}, not from
     * the isolation level. SERIALIZABLE would add retry-on-conflict handling for a
     * guarantee the lock already provides.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SyncResponse sync(AuthenticatedUser principal, SyncRequest request) {
        UUID userId = principal.userId();

        if (request.ops().size() > MAX_OPS_PER_PUSH) {
            throw new IllegalArgumentException(
                    "A push may carry at most %d ops, got %d".formatted(MAX_OPS_PER_PUSH, request.ops().size()));
        }

        List<UUID> applied = push(userId, principal.nodeId(), request.ops());
        return pull(principal, request.sinceSeq(), applied);
    }

    // ------------------------------------------------------------------ push

    private List<UUID> push(UUID userId, String nodeId, List<WireChange> ops) {
        if (ops.isEmpty()) {
            return List.of();
        }

        // Decode every op before touching the database. Validation used to happen at the
        // controller boundary via bean-validation annotations; now that the wire type is
        // shared with the client, ChangeCodec is the single arbiter of what is well-formed
        // -- so the rejection has to be pulled forward explicitly. A batch is all-or-
        // nothing: one malformed op fails the request rather than committing its
        // well-formed neighbours and leaving the client to work out which landed.
        List<Change> decoded = ops.stream().map(ChangeCodec::decode).toList();

        // Idempotency: ops this user has already had accepted are reported as applied but
        // not re-merged or re-logged. This is what makes a timed-out push safe to replay.
        Set<UUID> alreadyApplied =
                changeLog.findAlreadyApplied(userId, ops.stream().map(WireChange::opId).toList());

        List<WireChange> fresh = new ArrayList<>();
        List<Change> freshDecoded = new ArrayList<>();
        List<UUID> acknowledged = new ArrayList<>();
        Set<UUID> seenInThisBatch = new java.util.HashSet<>();

        for (int i = 0; i < ops.size(); i++) {
            WireChange op = ops.get(i);
            acknowledged.add(op.opId());
            // A client can repeat an op id within one request; accept it once.
            if (!alreadyApplied.contains(op.opId()) && seenInThisBatch.add(op.opId())) {
                fresh.add(op);
                freshDecoded.add(decoded.get(i));
            }
        }

        if (fresh.isEmpty()) {
            return acknowledged;
        }

        // Allocate first, inside this transaction, holding the user's counter row until
        // commit. See ADR-002 -- this is what makes sequence order equal commit order.
        long startSeq = changeLog.allocateSequenceRange(userId, fresh.size());
        changeLog.append(userId, startSeq, fresh, nodeId);

        // The log is the record of what the client said; entity state is the record of
        // what it means. Both are written here, in that order, inside one transaction.
        for (Change change : freshDecoded) {
            applyToEntityState(userId, change);
        }

        return acknowledged;
    }

    /** Merges one change into stored entity state using the shared engine. */
    private void applyToEntityState(UUID userId, Change change) {
        String type = change.entityType().name();

        EntityRecord current = entities
                .load(userId, type, change.entityId())
                .map(stored -> toCoreRecord(change.entityType(), change.entityId(), stored))
                .orElse(null);

        var result = mergeEngine.merge(current, change);

        for (Resolution resolution : result.superseded()) {
            // Every discarded write is recorded. Silent loss is a bug; explained loss
            // under a documented rule is a trade-off (ADR-003).
            log.debug(
                    "Superseded {} on {}/{}: incoming {} lost to {}",
                    resolution.target(),
                    type,
                    change.entityId(),
                    resolution.incoming(),
                    resolution.existing());
        }

        entities.save(userId, type, change.entityId(), toStored(result.state()));
    }

    // ------------------------------------------------------------------ pull

    private SyncResponse pull(AuthenticatedUser principal, long sinceSeq, List<UUID> appliedOpIds) {
        UUID userId = principal.userId();
        long currentSeq = changeLog.currentSequence(userId);

        // GC horizon check (ADR-003). If the oldest retained change is newer than the
        // client's watermark + 1, changes it has never seen have already been collected,
        // and serving the remainder would leave it silently missing tombstones. A full
        // resync is the only safe answer.
        var oldestRetained = changeLog.oldestRetainedSequence(userId);
        if (sinceSeq > 0 && oldestRetained.isPresent() && oldestRetained.get() > sinceSeq + 1) {
            log.info(
                    "Device {} requested sinceSeq={} but oldest retained is {}; forcing resync",
                    principal.deviceId(),
                    sinceSeq,
                    oldestRetained.get());
            return new SyncResponse(
                    appliedOpIds,
                    List.of(),
                    currentSeq,
                    false,
                    true,
                    "watermarkBelowGcHorizon",
                    clock.millis(),
                    dev.thompgt.habitsync.replication.dto.SyncDtos.PROTOCOL_VERSION);
        }

        // Ask for one extra row to detect "more remains" without a second COUNT query.
        var page = changeLog.readSince(userId, sinceSeq, MAX_PAGE_SIZE + 1);
        boolean hasMore = page.size() > MAX_PAGE_SIZE;
        if (hasMore) {
            page = page.subList(0, MAX_PAGE_SIZE);
        }

        List<SyncChangeEnvelope> envelopes =
                page.stream().map(c -> new SyncChangeEnvelope(c.serverSeq(), c.change())).toList();

        // nextSeq is the last sequence actually included, NOT the server's current head.
        // Reporting the head would advance the client past changes this page omitted.
        long nextSeq = envelopes.isEmpty() ? sinceSeq : envelopes.get(envelopes.size() - 1).serverSeq();

        // The client is only known to have reached its previous watermark; this page is
        // not durable on the device until it says so on its next request.
        accounts.recordDeviceProgress(userId, principal.deviceId(), sinceSeq, clock.instant());

        return new SyncResponse(
                appliedOpIds,
                envelopes,
                nextSeq,
                hasMore,
                false,
                null,
                clock.millis(),
                dev.thompgt.habitsync.replication.dto.SyncDtos.PROTOCOL_VERSION);
    }

    // ----------------------------------------------------------- translation

    private static EntityRecord toCoreRecord(
            EntityType type, UUID id, EntityRepository.StoredEntity stored) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        Map<String, Hlc> clocks = new LinkedHashMap<>();
        stored.fieldValues().forEach((name, value) -> {
            fields.put(name, FieldValue.of(value));
            clocks.put(name, Hlc.parse(stored.fieldClocks().get(name)));
        });
        return new EntityRecord(type, id, fields, clocks, stored.deleted(), stored.lifecycleClock());
    }

    private static EntityRepository.StoredEntity toStored(EntityRecord record) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> clocks = new LinkedHashMap<>();
        record.fields().forEach((name, value) -> {
            values.put(name, value.raw());
            clocks.put(name, record.clockOf(name).toCompactString());
        });
        return new EntityRepository.StoredEntity(
                record.deleted(),
                record.lifecycleClock() == null ? null : record.lifecycleClock().toCompactString(),
                values,
                clocks);
    }
}
