package com.foodmap.ingestion.snapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Ends the snapshot process once its run is reported: closes the Spring
 * context (worker threads, pool, scheduler) and exits the JVM with the run's
 * exit code — nothing keeps running afterwards. Replaced in tests.
 */
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class SnapshotExit {

    private final ApplicationContext context;

    public void exit(int code) {
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
