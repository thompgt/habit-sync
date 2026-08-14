package dev.thompgt.habitsync.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import dev.thompgt.habitsync.auth.AuthResult;
import dev.thompgt.habitsync.auth.AuthService;
import dev.thompgt.habitsync.replication.dto.SyncDtos;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncRequest;
import dev.thompgt.habitsync.security.AuthenticatedUser;
import dev.thompgt.habitsync.support.AbstractIntegrationTest;
import dev.thompgt.habitsync.sync.WireChange;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The scheduled entry point, as opposed to {@link RetentionTest}, which drives collection
 * one account at a time.
 *
 * <p>The distinction is the entire reason this class exists. {@code sweep()} used to call
 * {@code collectFor} on {@code this}, which skips the {@code @Transactional} proxy, so the
 * transaction boundary the collector documents existed only for callers coming in from
 * outside — which is to say, only for tests. Every test passed and no production sweep had
 * ever run in a transaction. Exercising collection exclusively through {@code collectFor}
 * could not have caught it, so the regression test has to go through {@code sweep()}.
 *
 * <p>Time is moved rather than waited for: the clock is offset an hour into the future
 * against a one-minute retention window, which makes rows written by the test count as long
 * expired without anything sleeping.
 */
@Import(RetentionSweepTest.FutureClockConfig.class)
@TestPropertySource(properties = "sync.retention=PT1M")
class RetentionSweepTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FutureClockConfig {
        @Bean
        @Primary
        Clock aheadByAnHour() {
            return Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        }
    }

    @Autowired private RetentionService retention;
    @Autowired private SyncService syncService;
    @Autowired private AuthService authService;
    @MockitoSpyBean private ChangeLogRepository changeLog;

    private AuthenticatedUser accountWithChanges(int changeCount) {
        AuthResult account = authService.register(
                "sweep-" + UUID.randomUUID() + "@example.com", "correct-horse-battery-staple", "Phone");
        AuthenticatedUser device = new AuthenticatedUser(account.userId(), account.deviceId());

        UUID habit = UUID.randomUUID();
        for (int i = 1; i <= changeCount; i++) {
            syncService.sync(
                    device,
                    new SyncRequest(
                            i - 1L,
                            SyncDtos.PROTOCOL_VERSION,
                            List.of(new WireChange(
                                    UUID.randomUUID(),
                                    "HABIT",
                                    habit,
                                    "UPSERT",
                                    (i * 1000) + ":0:device-a",
                                    Map.of("f" + i, "v")))));
        }
        return device;
    }

    @Test
    @DisplayName("collection during a sweep runs inside a transaction")
    void sweepCollectsTransactionally() {
        accountWithChanges(2);

        // retentionHorizon is the collector's first database call, so whatever it observes
        // is the transaction state collection as a whole is running under.
        AtomicBoolean transactionalAtCollectionTime = new AtomicBoolean();
        doAnswer(invocation -> {
                    transactionalAtCollectionTime.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return invocation.callRealMethod();
                })
                .when(changeLog)
                .retentionHorizon(any(), any());

        retention.sweep();

        assertThat(transactionalAtCollectionTime)
                .as("sweep() must reach collectFor through the proxy, not through `this`")
                .isTrue();
    }

    @Test
    @DisplayName("a sweep collects expired accounts, and the next one can still run")
    void sweepIsRepeatable() {
        UUID first = accountWithChanges(3).userId();

        retention.sweep();

        assertThat(changeLog.oldestRetainedSequence(first))
                .as("everything predates the cutoff, so the whole log is collectable")
                .isEmpty();

        // The advisory lock is session-scoped and belongs to the connection that took it. If
        // it were released through the pool rather than on that connection, the unlock would
        // land on the wrong session, the real lock would stay held, and every later sweep
        // would silently skip. A second sweep that collects a second account — written after
        // the first sweep finished, so it cannot have been collected by it — is what rules
        // that out.
        UUID second = accountWithChanges(2).userId();
        assertThat(changeLog.oldestRetainedSequence(second)).isPresent();

        retention.sweep();

        assertThat(changeLog.oldestRetainedSequence(second)).isEmpty();
    }
}
