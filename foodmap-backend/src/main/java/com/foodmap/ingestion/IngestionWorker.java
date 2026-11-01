package com.foodmap.ingestion;

import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.service.RestaurantSyncService;
import com.foodmap.restaurant.service.RestaurantSyncService.PlaceSyncResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Drains IngestionQueue in a small fixed-thread pool. Each task runs the
 * keyword fanout → Place Detail → upsert pipeline and records the resulting
 * coverage row in place_cells.
 *
 * Why a dedicated ExecutorService and not @Async?
 *   @Async is great for one-shot fire-and-forget tasks. For a long-running
 *   consumer loop, a dedicated pool with explicit start/stop lifecycle is
 *   simpler to reason about — and we control thread names for log clarity.
 *
 * Why no @Transactional on run()?
 *   The transactional boundary belongs around the *unit of work* (one
 *   ensureFreshPlace upsert), not the whole consumer loop. Holding one
 *   transaction open for the lifetime of the worker would defeat connection
 *   pooling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionWorker {

    private static final long POLL_TIMEOUT_MS = 500L;

    private final IngestionQueue queue;
    private final RestaurantSyncService restaurantSyncService;
    private final KeywordFanoutService keywordFanoutService;
    private final CellCoverageService cellCoverageService;
    private final IngestionProperties properties;
    private final IngestionMetrics metrics;

    private ExecutorService pool;
    private volatile boolean running = false;

    @PostConstruct
    public void start() {
        int workers = Math.max(1, properties.getWorkers());
        pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread t = new Thread(runnable);
            t.setName("ingestion-worker-" + t.getId());
            t.setDaemon(true);   // do not block JVM shutdown on these threads
            return t;
        });
        running = true;
        for (int i = 0; i < workers; i++) {
            pool.submit(this::runLoop);
        }
        log.info("IngestionWorker started with {} workers", workers);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("IngestionWorker stopped");
    }

    private void runLoop() {
        log.debug("Worker loop started on thread {}", Thread.currentThread().getName());
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                IngestionTask task = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (task == null) continue;     // timeout — just loop and check `running`
                processTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Never let an unexpected exception kill the worker thread —
                // log and keep going. The cell is already marked completed
                // inside processTask's finally block.
                log.error("Unexpected error in ingestion worker", e);
            }
        }
        log.debug("Worker loop exiting on thread {}", Thread.currentThread().getName());
    }

    /**
     * Process one cell. Wrapped in a try/finally to guarantee the
     * in-flight reservation (in IngestionQueue) is released even on
     * failure, and that exactly one of recordCrawlSuccess /
     * recordCrawlFailure runs so the cooldown gate is always advanced.
     *
     * Every resolved place is synced, but only those within the cell's
     * crawl radius count as coverage (place_count / cooldown).
     */
    void processTask(IngestionTask task) {
        log.debug("Ingesting cell {} at ({}, {}) source={}",
            task.cellKey(), task.centerLat(), task.centerLng(), task.source());

        long startNanos = System.nanoTime();
        int placesResolved    = 0;
        int placesLocal       = 0;
        int placeDetailCalls  = 0;
        int autoCompleteCalls = 0;
        int placesDiscovered  = 0;
        String failureReason  = null;
        FanoutResult fanout   = null;

        try {
            fanout = keywordFanoutService.discoverPlaceIds(
                task.centerLat(),
                task.centerLng(),
                CellCoverageService.CELL_RADIUS_METERS
            );
            autoCompleteCalls = fanout.autoCompleteCalls();
            placesDiscovered  = fanout.placeIds().size();

            if (fanout.allKeywordsFailed()) {
                // Every AutoComplete threw — treat as a transient outage,
                // not as an empty area. The retry path will back off.
                failureReason = "fanout_all_failed";
            } else {
                for (String placeId : fanout.placeIds()) {
                    PlaceSyncResult r = restaurantSyncService.ensureFreshPlace(placeId);
                    if (r.goongCalled())  placeDetailCalls++;
                    if (r.isPresent()) {
                        placesResolved++;
                        Restaurant restaurant = r.restaurant().get();
                        if (cellCoverageService.isWithinCrawlRadius(
                                task.centerLat(), task.centerLng(),
                                restaurant.getLatitude(), restaurant.getLongitude())) {
                            placesLocal++;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // ensureFreshPlace and KeywordFanoutService both swallow Goong
            // exceptions internally; anything thrown here is unexpected
            // (e.g. DB outage). Treat the cell as a failed attempt.
            failureReason = truncateReason(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Cell {} processing threw unexpectedly: {}", task.cellKey(), e.getMessage());
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            IngestionMetrics.Outcome outcome = recordOutcome(
                task, failureReason, placesLocal);

            metrics.recordCrawl(
                task.cellKey(),
                outcome,
                latencyMs,
                placesDiscovered,
                placesResolved,
                placesLocal,
                autoCompleteCalls,
                placeDetailCalls
            );
            queue.markCompleted(task.cellKey());
        }
    }

    /**
     * Apply the per-cell state machine and return the metrics outcome
     * bucket. Routes to CellCoverageService.recordCrawlSuccess or
     * recordCrawlFailure depending on what happened during processing.
     *
     * Wrapped in try/catch so that a DB hiccup recording coverage does
     * not leak through to the worker loop — the cell stays in-flight
     * tracking is already released by the caller's finally.
     */
    private IngestionMetrics.Outcome recordOutcome(
            IngestionTask task, String failureReason, int placesLocal) {
        try {
            if (failureReason != null) {
                cellCoverageService.recordCrawlFailure(
                    task.cellKey(), task.centerLat(), task.centerLng(),
                    task.source(), failureReason);
                return IngestionMetrics.Outcome.FAILED;
            }
            cellCoverageService.recordCrawlSuccess(
                task.cellKey(), task.centerLat(), task.centerLng(),
                task.source(), placesLocal);
            return placesLocal < properties.getLowDensityThreshold()
                ? IngestionMetrics.Outcome.LOW_DENSITY
                : IngestionMetrics.Outcome.SUCCESS;
        } catch (RuntimeException e) {
            log.warn("Failed to upsert place_cells row for {}: {}",
                task.cellKey(), e.getMessage());
            // Best-effort metric: classify as FAILED so we don't claim
            // success when the cooldown gate may not have been advanced.
            return IngestionMetrics.Outcome.FAILED;
        }
    }

    private static String truncateReason(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
