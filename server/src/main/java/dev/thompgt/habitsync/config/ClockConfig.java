package dev.thompgt.habitsync.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * Injected rather than called statically, so tests can advance time deliberately —
     * expiring a refresh token in a test should not require sleeping for 30 days.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
