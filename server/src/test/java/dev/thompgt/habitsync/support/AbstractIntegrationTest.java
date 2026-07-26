package dev.thompgt.habitsync.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real database.
 *
 * <p>Postgres, not H2. The behaviour this project depends on most —
 * {@code SELECT ... FOR UPDATE} serialising concurrent pushes per user (ADR-002) — is
 * precisely the kind of thing an in-memory database emulates loosely or not at all. A
 * test suite that passes on H2 and lies about locking is worse than no suite.
 *
 * <p>The container is a static singleton started once per JVM and deliberately never
 * stopped: Ryuk reaps it when the JVM exits. Starting a container per test class turns
 * a 30-second suite into a five-minute one.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("habitsync")
                    .withUsername("habitsync")
                    .withPassword("habitsync")
                    // Durability is irrelevant for a throwaway container and fsync
                    // dominates the runtime of write-heavy sync tests.
                    .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
