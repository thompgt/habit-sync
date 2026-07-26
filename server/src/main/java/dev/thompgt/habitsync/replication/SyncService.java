package dev.thompgt.habitsync.replication;

import dev.thompgt.habitsync.account.AccountRepository;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncChangeEnvelope;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncRequest;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncResponse;
import dev.thompgt.habitsync.security.AuthenticatedUser;
import dev.thompgt.habitsync.sync.Change;
import dev.thompgt.habitsync.sync.ChangeCodec;
import dev.thompgt.habitsync.sync.ClockDriftException;
import dev.thompgt.habitsync.sync.EntityRecord;
import dev.thompgt.habitsync.sync.EntityType;
import dev.thompgt.habitsync.sync.FieldValue;
import dev.thompgt.habitsync.sync.Hlc;
import dev.thompgt.habitsync.sync.HlcClock;
import dev.thompgt.habitsync.sync.MergeEngine;
import dev.thompgt.habitsync.sync.Resolution;
import dev.thompgt.habitsync.sync.WireChange;
import java.time.Clock;
import java.time.Duration;
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

    /**
     * How far ahead of server time an inbound HLC may claim to be (ADR-001).
     *
     * <p>Deliberately the same constant the client enforces in {@link HlcClock#observe}, so
     * the two ends of the protocol cannot disagree about what counts as plausible. A device
     * that would refuse a peer's timestamp must not be able to publish that timestamp itself.
     */
    public static final Duration MAX_CLOCK_DRIFT = HlcClock.DEFAULT_MAX_DRIFT;

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

        rejectImplausibleClocks(nodeId, decoded);

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

    /**
     * Refuses a batch carrying a timestamp implausibly far ahead of server time (ADR-001).
     *
     * <p>The client already applies this bound in {@link HlcClock#observe}, and that is not
     * enough. A receiving device's check protects only that device, only against peers it
     * happens to pull from, and only if it is running our client at all. The server is the
     * single point every write passes through, and — more to the point — it is the last
     * point at which a poisoned timestamp can be stopped <em>before it is in the log</em>.
     * Once appended, every device on the account faces the same two bad options forever:
     * absorb the skew, and no honest write ever wins another conflict; or reject the page,
     * and never sync again. Neither is recoverable by the user. Rejecting at the door is.
     *
     * <p>Only the upper bound is guarded. A timestamp far in the <em>past</em> is what a
     * device returning from a fortnight offline legitimately looks like, and it is harmless:
     * it loses conflicts, which is exactly what it should do.
     *
     * <p>All-or-nothing, like the decode above. A partial accept would leave the client to
     * work out which of its ops landed, and the answer would depend on the server's wall
     * clock at the instant of the request.
     *
     * <p>Known gap: an op already stamped with a bad clock stays in the client's outbox and
     * will be re-pushed, and re-rejected, even after the device's wall clock is corrected —
     * fixing the clock does not re-stamp what is already queued. Recovering needs the client
     * to re-stamp its outbox when a push is refused for drift; that is client work, tracked
     * separately.
     */
    private void rejectImplausibleClocks(String nodeId, List<Change> decoded) {
        long now = clock.millis();
        long limit = MAX_CLOCK_DRIFT.toMillis();
        for (Change change : decoded) {
            if (change.hlc().physicalMillis() > now + limit) {
                log.warn(
                        "Rejecting push from node {}: HLC {} is {} ms ahead of server time",
                        nodeId,
                        change.hlc(),
                        change.hlc().physicalMillis() - now);
                throw new ClockDriftException(change.hlc(), now, limit);
            }
        }
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

        if (sinceSeq == 0) {
            return bootstrap(principal, appliedOpIds);
        }

        // GC horizon check (ADR-003). If the oldest retained change is newer than the
        // client's watermark + 1, changes it has never seen have already been collected,
        // and serving the remainder would leave it silently missing tombstones. A full
        // resync is the only safe answer.
        //
        // An empty log is the same question with no oldest entry to ask it of. It means
        // either the account has never been written to, in which case sinceSeq cannot be
        // behind, or retention collected the log whole -- and then a client below the head
        // has missed everything and must be told so. Treating "no rows" as "nothing to
        // catch up on" would hand such a client an empty page and let it believe it was
        // current, which is the exact silent loss this check exists to prevent.
        var oldestRetained = changeLog.oldestRetainedSequence(userId);
        boolean missedCollectedChanges = oldestRetained
                .map(oldest -> oldest > sinceSeq + 1)
                .orElse(sinceSeq < currentSeq);
        if (sinceSeq > 0 && missedCollectedChanges) {
            log.info(
                    "Device {} requested sinceSeq={} but oldest retained is {} (head {}); forcing resync",
                    principal.deviceId(),
                    sinceSeq,
                    oldestRetained.map(String::valueOf).orElse("none — log fully collected"),
                    currentSeq);
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

    // ------------------------------------------------------------ bootstrap

    /**
     * Serves a device starting from nothing out of current entity state, not the log.
     *
     * <p>This is what makes log retention possible at all. Replaying the log to a device at
     * {@code sinceSeq = 0} requires the log to reach back to the account's first write
     * forever; the moment anything is collected, a bootstrap silently omits every entity
     * whose creating write fell in the collected range. That is not a corner case reserved
     * for long-offline devices — a tablet added to a two-year-old account bootstraps from
     * zero on the day it is bought.
     *
     * <p>Rebuilding from state instead makes the log purely a catch-up structure, which can
     * be truncated from the front (see {@link RetentionService}) without any device losing
     * data. It is also strictly cheaper: a snapshot is bounded by what the user currently
     * has, and the log by everything they have ever done.
     *
     * <h4>One change per field, not one per entity</h4>
     *
     * Each field register carries its own HLC (ADR-001), and a wire change carries one. A
     * single synthesised UPSERT per entity would have to flatten those clocks to one value,
     * which would hand the device wrong provenance for every field and make its next merge
     * decide conflicts incorrectly. Emitting a change per field preserves each register
     * exactly and needs no protocol change.
     *
     * <h4>Why the sequence is read first</h4>
     *
     * A push committing between the two reads leaves entity state <em>ahead</em> of
     * {@code snapshotSeq}, never behind — the sequence is only visible once the transaction
     * that allocated it has committed its entity writes too. The device therefore receives
     * those changes a second time on its next pull, and merge is idempotent, so applying
     * them twice is a no-op. Reading the sequence afterwards would produce the opposite and
     * fatal skew: a watermark past state the device never received.
     */
    private SyncResponse bootstrap(AuthenticatedUser principal, List<UUID> appliedOpIds) {
        UUID userId = principal.userId();
        long snapshotSeq = changeLog.currentSequence(userId);

        List<SyncChangeEnvelope> envelopes = new ArrayList<>();
        for (EntityRepository.StoredEntityRow row : entities.loadAll(userId)) {
            for (Map.Entry<String, String> field : row.entity().fieldValues().entrySet()) {
                envelopes.add(new SyncChangeEnvelope(
                        snapshotSeq,
                        new WireChange(
                                UUID.randomUUID(),
                                row.entityType(),
                                row.entityId(),
                                "UPSERT",
                                row.entity().fieldClocks().get(field.getKey()),
                                // A single-entry map, and not Map.of: a cleared field is a
                                // null value, which Map.of rejects outright.
                                java.util.Collections.singletonMap(field.getKey(), field.getValue()))));
            }
            String lifecycle = row.entity().lifecycleHlc();
            if (lifecycle != null) {
                envelopes.add(new SyncChangeEnvelope(
                        snapshotSeq,
                        new WireChange(
                                UUID.randomUUID(),
                                row.entityType(),
                                row.entityId(),
                                row.entity().deleted() ? "DELETE" : "RESTORE",
                                lifecycle,
                                Map.of())));
            }
        }

        log.info(
                "Bootstrapping device {} from a snapshot at seq {}: {} changes",
                principal.deviceId(),
                snapshotSeq,
                envelopes.size());

        accounts.recordDeviceProgress(userId, principal.deviceId(), 0, clock.instant());

        // Sent whole rather than paged. Unlike the log, a snapshot is bounded by the user's
        // live entities rather than by their history, so it does not grow without limit —
        // but it is not bounded by MAX_PAGE_SIZE either, and paging it would need a cursor
        // the protocol does not carry, since synthesised changes have no sequence of their
        // own to resume from. Worth revisiting if accounts ever get large.
        return new SyncResponse(
                appliedOpIds,
                envelopes,
                snapshotSeq,
                false,
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
