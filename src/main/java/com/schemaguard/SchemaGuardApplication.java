package com.schemaguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EnableScheduling activates the @Scheduled polling loop in IndexWorker.
 * Has no effect on tests (IndexWorker is @Profile("redis") only).
 */
@SpringBootApplication
@EnableScheduling
public class SchemaGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemaGuardApplication.class, args);
    }
}
