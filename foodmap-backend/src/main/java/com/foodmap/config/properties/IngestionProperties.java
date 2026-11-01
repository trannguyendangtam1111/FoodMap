package com.foodmap.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Knobs for the background ingestion pipeline.
 * Bound at app.ingestion.* in application.yml.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ingestion")
public class IngestionProperties {

    /**
     * Maximum number of cells queued at once. When full, new enqueue
     * requests are silently dropped (back-pressure — read path stays fast).
     * 1000 cells × ~0.7 km² per cell ≈ enough buffer for a full city.
     */
    private int queueCapacity = 1000;

    /**
     * Number of worker threads draining the queue. Two is enough because
     * the real throughput ceiling is the Goong rate limit (10 rps),
     * not CPU. More workers just means more contention on the limiter.
     */
    private int workers = 1;

    // ── Snapshot mode: every Goong trigger is OFF unless explicitly enabled ──
    // Goong is a manually controlled ingestion source; the PostgreSQL
    // snapshot serves every user flow. Each trigger below must be switched
    // on deliberately (env var) for a controlled ingestion run.

    /**
     * If true, the SeedCrawler walks the HCMC seed cells on startup.
     * Off by default: a restart must never spend Goong quota.
     */
    private boolean seedOnStartup = false;

    /**
     * If true, map viewport / nearby requests enqueue stale cells for
     * ingestion. Off by default: browsing reads PostgreSQL only.
     */
    private boolean viewportEnqueueEnabled = false;

    /**
     * If true, the nightly RestaurantSyncScheduler re-ingests the oldest
     * cells. Off by default: no unattended recrawl.
     */
    private boolean scheduledRecrawlEnabled = false;

    /**
     * Daily refresh budget for the recrawl scheduler — at most N
     * oldest cells per nightly run (only when scheduledRecrawlEnabled).
     */
    private int dailyRecrawlBudget = 50;

    /**
     * Broad-category seed list fanned out per cell against Goong AutoComplete.
     *
     * Phase-1 deliberately ships only {@code restaurant} and {@code cafe} —
     * these two cover ≈ 95% of food places at city scale while keeping the
     * per-cell call budget tiny (2 AutoComplete calls instead of 8+).
     *
     * Cuisine/dish-level enrichment (phở, sushi, buffet, …) does NOT happen
     * here. Once a place is in the local DB, classification can be derived
     * offline from its name/category via a background enrichment job — no
     * extra provider quota required.
     */
    private List<String> keywords = List.of("restaurant", "cafe");

    /**
     * Hard cap on the number of keywords actually queried per cell.
     * Matches {@link #keywords} size in Phase 1 — the limiter is here so
     * that a misconfigured longer list cannot blow up provider quota.
     */
    private int maxKeywordsPerCell = 2;

    // ── Crawl outcome cooldowns ─────────────────────────────────────────

    /** Cooldown after a successful crawl with ≥ lowDensityThreshold places. */
    private int freshCooldownDays = 30;

    /**
     * Below this many places, treat the cell as low-density — the area
     * may genuinely be sparse, or our keyword list missed it. Recrawl
     * sooner than a fresh cell so a future enrichment can fill in.
     */
    private int lowDensityThreshold = 3;

    /** Cooldown after a successful crawl with < lowDensityThreshold places. */
    private int lowDensityCooldownHours = 6;

    // ── Retry / backoff ─────────────────────────────────────────────────

    /** First retry happens this many minutes after the failure. */
    private int retryBaseBackoffMinutes = 2;

    /** Backoff doubles each attempt up to this ceiling. */
    private int retryMaxBackoffMinutes = 60;

    /** After this many consecutive failures, status flips to FAILED. */
    private int maxRetryAttempts = 3;

    /** Cooldown applied once a cell is marked FAILED. */
    private int failedCooldownHours = 24;
}
