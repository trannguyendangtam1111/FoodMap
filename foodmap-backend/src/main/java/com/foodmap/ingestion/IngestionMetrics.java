package com.foodmap.ingestion;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight metrics for the viewport enrichment workflow.
 *
 * Why hand-rolled and not Micrometer?
 *   spring-boot-starter-actuator + micrometer-registry-* are not on
 *   the classpath. Adding them just to record six counters and one
 *   histogram bucket would be scope creep. This class emits the same
 *   information two ways:
 *     - in-process AtomicLong counters, exposed via {@link #snapshot()}
 *       for future admin endpoints or test assertions
 *     - one structured INFO log line per completed crawl, parseable by
 *       any log scraper (key=value pairs, stable schema)
 *
 *   When Micrometer arrives later, this class becomes a thin adapter —
 *   replace the AtomicLongs with Counter/Timer and the log line stays.
 */
@Slf4j
@Component
public class IngestionMetrics {

    /** Crawl outcome buckets. */
    public enum Outcome {
        SUCCESS,        // ≥ threshold places within the crawl radius
        LOW_DENSITY,    // crawl succeeded but found < threshold local places
        FAILED          // all keywords failed — transient or permanent
    }

    private final AtomicLong crawlsSucceeded   = new AtomicLong();
    private final AtomicLong crawlsLowDensity  = new AtomicLong();
    private final AtomicLong crawlsFailed      = new AtomicLong();
    private final AtomicLong placesDiscovered  = new AtomicLong();
    private final AtomicLong placesUpserted    = new AtomicLong();
    private final AtomicLong autoCompleteCalls = new AtomicLong();
    private final AtomicLong placeDetailCalls  = new AtomicLong();
    private final AtomicLong totalLatencyMs    = new AtomicLong();

    /**
     * Record one completed crawl. Increments the matching counters and
     * emits a single structured log line at INFO so a log scraper can
     * build time-series without joining multiple events.
     *
     * @param placesUpserted places that resolved to a restaurant row (any distance)
     * @param placesLocal    the subset within the cell's crawl radius — the coverage count
     */
    public void recordCrawl(
            String cellKey,
            Outcome outcome,
            long latencyMs,
            int placesDiscovered,
            int placesUpserted,
            int placesLocal,
            int autoCompleteCalls,
            int placeDetailCalls) {

        switch (outcome) {
            case SUCCESS     -> crawlsSucceeded.incrementAndGet();
            case LOW_DENSITY -> crawlsLowDensity.incrementAndGet();
            case FAILED      -> crawlsFailed.incrementAndGet();
        }
        this.placesDiscovered.addAndGet(placesDiscovered);
        this.placesUpserted.addAndGet(placesUpserted);
        this.autoCompleteCalls.addAndGet(autoCompleteCalls);
        this.placeDetailCalls.addAndGet(placeDetailCalls);
        this.totalLatencyMs.addAndGet(latencyMs);

        // Stable key=value schema. Scrapers can pin on the literal
        // "ingestion.crawl" token to extract these events.
        log.info("ingestion.crawl cell={} outcome={} latency_ms={} discovered={} resolved={} local={} ac_calls={} pd_calls={}",
            cellKey, outcome, latencyMs, placesDiscovered, placesUpserted, placesLocal,
            autoCompleteCalls, placeDetailCalls);
    }

    /** Snapshot of all counters — useful for tests and future admin probes. */
    public Snapshot snapshot() {
        return new Snapshot(
            crawlsSucceeded.get(),
            crawlsLowDensity.get(),
            crawlsFailed.get(),
            placesDiscovered.get(),
            placesUpserted.get(),
            autoCompleteCalls.get(),
            placeDetailCalls.get(),
            totalLatencyMs.get()
        );
    }

    @Value
    public static class Snapshot {
        long crawlsSucceeded;
        long crawlsLowDensity;
        long crawlsFailed;
        long placesDiscovered;
        long placesUpserted;
        long autoCompleteCalls;
        long placeDetailCalls;
        long totalLatencyMs;
    }
}
