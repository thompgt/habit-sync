package dev.thompgt.habitsync.replication;

import dev.thompgt.habitsync.account.AccountRepository;
import dev.thompgt.habitsync.account.Device;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tombstone and change-log collector promised by ADR-003.
 *
 * <p>Correctness-critical infrastructure rather than housekeeping. Tombstones cannot be
 * kept forever — the log would grow without bound — and cannot be dropped freely either,
 * because dropping one a device has not seen lets that device resurrect the entity. What
 * makes collection safe is not this job being careful; it is the pull-side horizon check
 * in {@link SyncService}, which refuses to serve incrementally any device that might have
 * missed something and demands a bootstrap instead. This job's only obligation is to leave
 * the log in a shape that check can reason about.
 *
 * <h2>What that shape is</h2>
 *
 * A <b>contiguous suffix</b>. The horizon check asks whether the oldest surviving sequence
 * is above the client's watermark, which answers "has this client missed anything" only if
 * nothing survives above a gap. Collection is therefore expressed as a prefix delete up to
 * a horizon sequence, never as a time range — see
 * {@link ChangeLogRepository#retentionHorizon} for why those are not the same thing.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It does not wait for the slowest device. Bounding collection by the minimum
 * {@code last_seen_seq} sounds safer and is worse: one phone lost down the back of a sofa
 * pins its account's log forever, which is precisely the unbounded growth retention exists
 * to stop. Devices left behind are logged, and pay a bootstrap the next time they appear.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final ChangeLogRepository changeLog;
    private final EntityRepository entities;
    private final AccountRepository accounts;
    private final Clock clock;
    private final Duration retention;

    public RetentionService(
            ChangeLogRepository changeLog,
            EntityRepository entities,
            AccountRepository accounts,
            Clock clock,
            @Value("${sync.retention:P90D}") Duration retention) {
        this.changeLog = changeLog;
        this.entities = entities;
        this.accounts = accounts;
        this.clock = clock;
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Retention must be positive, got " + retention);
        }
        this.retention = retention;
    }

    /**
     * Runs the sweep across every account.
     *
     * <p>Nightly and off-peak. The frequency is not load-bearing: a sweep that is skipped,
     * or runs a week late, costs disk and nothing else — the horizon check does not depend
     * on collection having happened, only on what it left behind.
     */
    @Scheduled(cron = "${sync.retention-cron:0 30 3 * * *}")
    public void sweep() {
        Instant cutoff = clock.instant().minus(retention);
        List<UUID> users = changeLog.usersWithLoggedChanges();

        int logRows = 0;
        int tombstones = 0;
        for (UUID userId : users) {
            // Per user, per transaction. A sweep across thousands of accounts must not hold
            // one long transaction, and a failure partway through should leave the accounts
            // it already collected collected — each account's log is independent, so there
            // is nothing to be consistent about between them.
            Collected collected = collectFor(userId, cutoff);
            logRows += collected.logRows();
            tombstones += collected.tombstones();
        }

        log.info(
                "Retention sweep over {} accounts (cutoff {}): removed {} log entries and {} tombstoned entities",
                users.size(),
                cutoff,
                logRows,
                tombstones);
    }

    /**
     * Collects one account. Public so a test, or an operator, can run it deterministically
     * without waiting for the schedule.
     */
    @Transactional
    public Collected collectFor(UUID userId, Instant cutoff) {
        Optional<Long> horizon = changeLog.retentionHorizon(userId, cutoff);

        int logRows = 0;
        if (horizon.isPresent()) {
            warnAboutDevicesLeftBehind(userId, horizon.get());
            logRows = changeLog.deleteUpTo(userId, horizon.get());
        }

        int tombstones = entities.deleteTombstonesOlderThan(userId, cutoff);
        return new Collected(logRows, tombstones);
    }

    private void warnAboutDevicesLeftBehind(UUID userId, long horizon) {
        // Strictly below the horizon: a device sitting exactly on it has seen every entry
        // about to be deleted and is still served incrementally.
        List<Device> behind = accounts.findDevicesBehind(userId, horizon);
        for (Device device : behind) {
            log.warn(
                    "Collecting log up to seq {} for user {} leaves device {} ({}) behind at seq {}; "
                            + "it will be told to resync",
                    horizon,
                    userId,
                    device.id(),
                    device.displayName(),
                    device.lastSeenSeq());
        }
    }

    /** What one account's collection removed. */
    public record Collected(int logRows, int tombstones) {}
}
