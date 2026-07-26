package dev.thompgt.habitsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HabitSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(HabitSyncApplication.class, args);
    }
}
