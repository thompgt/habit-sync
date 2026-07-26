package dev.thompgt.habitsync.replication;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thompgt.habitsync.auth.AuthResult;
import dev.thompgt.habitsync.auth.AuthService;
import dev.thompgt.habitsync.replication.dto.SyncChange;
import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ADR-002 regression test: concurrent pushes for one user must never let a reader
 * advance its watermark past a change that has not committed yet.
 *
 * <p>This is the test that justifies the whole per-user counter design. With a plain
 * {@code BIGSERIAL} it fails — a transaction holding seq N commits after one holding
 * N+1, a reader in between sees N+1, advances past N, and N is lost forever.
 *
 * <p>It must run against real Postgres. The failure depends on MVCC visibility and row
 * locking, which an in-memory database does not reproduce.
 */
class ChangeLogOrderingTest extends AbstractIntegrationTest {

    @Autowired private ChangeLogRepository changeLog;
    @Autowired private AuthService authService;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;

    private UUID freshUser() {
        AuthResult account = authService.register(
                "seq-" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple", "d");
        return account.userId();
    }

    private static SyncChange change() {
        return new SyncChange(
                UUID.randomUUID(),
                "HABIT",
                UUID.randomUUID(),
                "UPSERT",
                "1000:0:node-a",
                Map.of("name", "Run"));
    }

    @Test
    @DisplayName("a concurrent reader never observes a gap that later fills in")
    void concurrentPushesNeverExposeAGapToAReader() throws Exception {
        UUID userId = freshUser();
        int writers = 8;
        int pushesPerWriter = 15;

        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicBoolean writingDone = new AtomicBoolean(false);
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();

        // Reader: repeatedly pulls incrementally, exactly as a client does, and records
        // every sequence it observes.
        List<Long> observed = new ArrayList<>();
        var readerTask = pool.submit(() -> {
            startLine.await();
            long watermark = 0;
            while (!writingDone.get()) {
                var page = changeLog.readSince(userId, watermark, 100);
                for (var entry : page) {
                    observed.add(entry.serverSeq());
                    watermark = entry.serverSeq();
                }
            }
            // Final drain after writers finish.
            var page = changeLog.readSince(userId, watermark, 1000);
            for (var entry : page) {
                observed.add(entry.serverSeq());
            }
            return null;
        });

        List<java.util.concurrent.Future<?>> writerTasks = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            writerTasks.add(pool.submit(() -> {
                startLine.await();
                for (int i = 0; i < pushesPerWriter; i++) {
                    transactions.executeWithoutResult(status -> {
                        long start = changeLog.allocateSequenceRange(userId, 1);
                        changeLog.append(userId, start, List.of(change()), "node-a");
                    });
                }
                return null;
            }));
        }

        startLine.countDown();
        for (var task : writerTasks) {
            task.get(60, TimeUnit.SECONDS);
        }
        writingDone.set(true);
        readerTask.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        int expected = writers * pushesPerWriter;

        // The reader must have seen every sequence exactly once, in strictly increasing
        // order. A skipped-then-appearing sequence is the data-loss bug.
        assertThat(observed).as("reader must observe every change exactly once").hasSize(expected);
        for (int i = 1; i < observed.size(); i++) {
            if (observed.get(i) <= observed.get(i - 1)) {
                violations.add("non-monotonic at index %d: %d then %d"
                        .formatted(i, observed.get(i - 1), observed.get(i)));
            }
        }
        assertThat(violations).isEmpty();
        assertThat(observed.get(observed.size() - 1)).isEqualTo((long) expected);
    }

    @Test
    @DisplayName("sequences are gapless, so a watermark can never skip a change")
    void allocatedSequencesAreContiguous() {
        UUID userId = freshUser();

        transactions.executeWithoutResult(status -> {
            long start = changeLog.allocateSequenceRange(userId, 3);
            changeLog.append(userId, start, List.of(change(), change(), change()), "node-a");
        });
        transactions.executeWithoutResult(status -> {
            long start = changeLog.allocateSequenceRange(userId, 2);
            changeLog.append(userId, start, List.of(change(), change()), "node-a");
        });

        List<Long> sequences = jdbc.queryForList(
                "SELECT server_seq FROM change_log WHERE user_id = ? ORDER BY server_seq",
                Long.class,
                userId);

        assertThat(sequences).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void currentSequenceReflectsWhatHasBeenAllocated() {
        UUID userId = freshUser();
        assertThat(changeLog.currentSequence(userId)).isZero();

        transactions.executeWithoutResult(status -> {
            long start = changeLog.allocateSequenceRange(userId, 4);
            changeLog.append(userId, start, List.of(change(), change(), change(), change()), "node-a");
        });

        assertThat(changeLog.currentSequence(userId)).isEqualTo(4L);
    }

    @Test
    @DisplayName("different users never block each other")
    void allocationIsScopedPerUser() {
        UUID userA = freshUser();
        UUID userB = freshUser();

        transactions.executeWithoutResult(status -> {
            long start = changeLog.allocateSequenceRange(userA, 2);
            changeLog.append(userA, start, List.of(change(), change()), "node-a");
        });
        transactions.executeWithoutResult(status -> {
            long start = changeLog.allocateSequenceRange(userB, 1);
            changeLog.append(userB, start, List.of(change()), "node-b");
        });

        // Each user's log starts at 1 -- sequences are per-user, not global.
        assertThat(changeLog.readSince(userA, 0, 10)).extracting("serverSeq").containsExactly(1L, 2L);
        assertThat(changeLog.readSince(userB, 0, 10)).extracting("serverSeq").containsExactly(1L);
    }
}
