package dev.thompgt.habitsync.replication;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    /**
     * Advisory-lock key for the sweep, so only one replica collects at a time.
     *
     * <p>An arbitrary but fixed constant. Postgres advisory locks share one global namespace
     * across the database, so the value only has to avoid colliding with any other advisory
     * lock this application takes — it is not derived from anything and must not be changed,
     * because two versions of the server using different keys would not exclude each other.
     */
    private static final long SWEEP_LOCK_KEY = 0x48425F52544E3031L; // "HB_RTN01"

    private final RetentionCollector collector;
    private final ChangeLogRepository changeLog;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retention;

    public RetentionService(
            RetentionCollector collector,
            ChangeLogRepository changeLog,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${sync.retention:P90D}") Duration retention) {
        this.collector = collector;
        this.changeLog = changeLog;
        this.jdbc = jdbc;
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
     *
     * <p>Guarded by an advisory lock because {@code @Scheduled} fires on <em>every</em>
     * replica. Two servers sweeping the same account concurrently is not a data-loss risk —
     * the deletes are idempotent and each runs in its own transaction — but they contend on
     * the same rows for no benefit, and the log lines interleave into something no operator
     * can read. A replica that does not get the lock skips the run entirely rather than
     * waiting for it: the next sweep is tomorrow, and blocking a scheduler thread overnight
     * to duplicate work already being done is the worse of the two.
     */
    @Scheduled(cron = "${sync.retention-cron:0 30 3 * * *}")
    public void sweep() {
        boolean ran = withSweepLock(this::sweepAllAccounts);
        if (!ran) {
            log.info("Retention sweep skipped: another replica holds the sweep lock");
        }
    }

    private void sweepAllAccounts() {
        Instant cutoff = clock.instant().minus(retention);
        List<UUID> users = changeLog.usersWithLoggedChanges();

        int logRows = 0;
        int tombstones = 0;
        int failed = 0;
        for (UUID userId : users) {
            try {
                Collected collected = collector.collectFor(userId, cutoff);
                logRows += collected.logRows();
                tombstones += collected.tombstones();
            } catch (RuntimeException e) {
                // One account's failure must not abandon the rest of the sweep. Accounts are
                // independent, collection is idempotent, and whatever went wrong here will
                // be retried on the next run — losing the remaining thousands of accounts to
                // one bad row is a far worse outcome than a logged failure.
                failed++;
                log.error("Retention collection failed for user {}; continuing sweep", userId, e);
            }
        }

        log.info(
                "Retention sweep over {} accounts (cutoff {}): removed {} log entries and {} tombstoned "
                        + "entities, {} accounts failed",
                users.size(),
                cutoff,
                logRows,
                tombstones,
                failed);
    }

    /**
     * Collects one account. Retained so a test, or an operator, can run collection
     * deterministically without waiting for the schedule.
     *
     * <p>Delegates across a bean boundary rather than doing the work here, which is what
     * gives the collection its transaction — see {@link RetentionCollector}.
     */
    public Collected collectFor(UUID userId, Instant cutoff) {
        return collector.collectFor(userId, cutoff);
    }

    /**
     * Runs {@code body} while holding the sweep's advisory lock, or returns false if another
     * replica holds it.
     *
     * <p>The lock is taken and released on <em>one pinned connection</em>, which is the whole
     * reason this is a {@link ConnectionCallback} rather than two {@code jdbc} calls. A
     * session-scoped advisory lock belongs to the connection that took it; issuing the unlock
     * through the pool would very likely run it on a different connection, where it does
     * nothing but log a warning — and the real lock would then be held until the borrowing
     * connection happened to be closed, which for a pooled connection may be never. The sweep
     * would succeed once and then be silently skipped forever after.
     *
     * <p>Holding a connection for the duration of the sweep is affordable: this runs nightly,
     * and the per-account transactions take their own connections from the pool alongside it.
     */
    private boolean withSweepLock(Runnable body) {
        Boolean acquired = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            if (!tryLock(connection)) {
                return false;
            }
            try {
                body.run();
            } finally {
                unlock(connection);
            }
            return true;
        });
        return Boolean.TRUE.equals(acquired);
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, SWEEP_LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, SWEEP_LOCK_KEY);
            statement.execute();
        } catch (SQLException e) {
            // Never fatal. The lock is released when this connection closes, and the only
            // cost of failing to release it now is that tonight's sweep — already finished —
            // blocks a peer that was going to skip anyway.
            log.warn("Failed to release the retention sweep advisory lock", e);
        }
    }

    /** What one account's collection removed. */
    public record Collected(int logRows, int tombstones) {}
}
