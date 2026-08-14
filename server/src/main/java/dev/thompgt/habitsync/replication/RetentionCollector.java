package dev.thompgt.habitsync.replication;

import dev.thompgt.habitsync.account.AccountRepository;
import dev.thompgt.habitsync.account.Device;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collects one account's expired log prefix and tombstones, in one transaction.
 *
 * <p>A separate bean from {@link RetentionService} on purpose, and the separation is
 * load-bearing rather than tidiness. {@code @Transactional} is applied by a proxy that wraps
 * the bean, so it only takes effect on calls arriving through that proxy from outside. When
 * {@code sweep()} held this method on the same class and called it as {@code
 * collectFor(...)}, the call went straight down {@code this} and skipped the proxy entirely:
 * the annotation was inert, and every scheduled sweep ran each repository call in its own
 * autocommit transaction instead of one per account.
 *
 * <p>That is not a theoretical difference. A sweep interrupted between {@link
 * ChangeLogRepository#deleteUpTo} and {@link EntityRepository#deleteTombstonesOlderThan}
 * would leave the log prefix collected and the tombstones it summarised still present —
 * survivable — but the reverse, a tombstone hard-deleted while the log entry announcing its
 * deletion is rolled back, resurrects an entity the user deleted. The transaction is what
 * rules that out, and only tests were getting one, because tests call this through the
 * proxy.
 *
 * <p>Crossing a bean boundary is the fix that keeps the annotation honest. Self-injection
 * would work too and reads as a puzzle six months later.
 */
@Service
public class RetentionCollector {

    private static final Logger log = LoggerFactory.getLogger(RetentionCollector.class);

    private final ChangeLogRepository changeLog;
    private final EntityRepository entities;
    private final AccountRepository accounts;

    public RetentionCollector(
            ChangeLogRepository changeLog, EntityRepository entities, AccountRepository accounts) {
        this.changeLog = changeLog;
        this.entities = entities;
        this.accounts = accounts;
    }

    /**
     * Collects one account.
     *
     * <p>Per account, per transaction. A sweep across thousands of accounts must not hold one
     * long transaction, and a failure partway through should leave the accounts it already
     * collected collected — each account's log is independent, so there is nothing to be
     * consistent about between them.
     */
    @Transactional
    public RetentionService.Collected collectFor(UUID userId, Instant cutoff) {
        Optional<Long> horizon = changeLog.retentionHorizon(userId, cutoff);

        int logRows = 0;
        if (horizon.isPresent()) {
            warnAboutDevicesLeftBehind(userId, horizon.get());
            logRows = changeLog.deleteUpTo(userId, horizon.get());
        }

        int tombstones = entities.deleteTombstonesOlderThan(userId, cutoff);
        return new RetentionService.Collected(logRows, tombstones);
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
}
