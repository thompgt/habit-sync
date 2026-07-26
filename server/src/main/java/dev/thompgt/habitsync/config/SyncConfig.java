package dev.thompgt.habitsync.config;

import dev.thompgt.habitsync.sync.MergeEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SyncConfig {

    /**
     * The merge engine the server uses is the same class the Android client uses.
     *
     * <p>It is stateless and a pure function, so a single shared instance is safe across
     * every request thread.
     */
    @Bean
    public MergeEngine mergeEngine() {
        return new MergeEngine();
    }
}
