package com.foodmap.scheduler;

import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.IngestionQueue;
import com.foodmap.ingestion.IngestionTask;
import com.foodmap.ingestion.CellCoverageService;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Daily recrawl: re-ingest the oldest place_cells through the same queue
 * the on-demand viewport path uses.
 *
 * Why this is different from the original (top-N-by-views) design:
 *   - "Views" was a Restaurant signal, but staleness lives on the *cell*.
 *     A cell that no user has loaded in 30 days might contain restaurants
 *     that have been there all along but whose phone/hours changed.
 *   - Using the same IngestionQueue + IngestionWorker as the viewport
 *     path means there is exactly one place in the codebase that calls
 *     Goong. Rate-limiting, retry, and back-pressure are all shared.
 *
 * Budget control:
 *   - dailyRecrawlBudget caps how many cells the cron enqueues per night.
 *     At ~10 rps the worker drains 50 cells in well under a minute.
 *
 * Off by default (snapshot mode): runs only with
 * app.ingestion.scheduled-recrawl-enabled=true.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestaurantSyncScheduler {

    private final PlaceCellRepository placeCellRepository;
    private final IngestionQueue ingestionQueue;
    private final CellCoverageService cellCoverageService;
    private final IngestionProperties ingestionProperties;

    /**
     * 03:00 server time — low-traffic window before the morning rush.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void refreshStaleCells() {
        // Snapshot mode (default): no unattended recrawl, no Goong quota spent.
        if (!ingestionProperties.isScheduledRecrawlEnabled()) {
            log.info("Daily recrawl: disabled (app.ingestion.scheduled-recrawl-enabled=false) — skipping.");
            return;
        }
        Instant threshold = Instant.now().minus(
            CellCoverageService.CELL_FRESHNESS_DAYS, ChronoUnit.DAYS);

        Page<PlaceCell> stale = placeCellRepository.findStaleCells(
            threshold, PageRequest.of(0, ingestionProperties.getDailyRecrawlBudget()));

        if (stale.isEmpty()) {
            log.info("Daily recrawl: no stale cells (everything is within {} days).",
                CellCoverageService.CELL_FRESHNESS_DAYS);
            return;
        }

        int enqueued = 0;
        for (PlaceCell cell : stale) {
            IngestionTask task = new IngestionTask(
                cell.getCellKey(),
                cell.getCenterLat(),
                cell.getCenterLng(),
                PlaceCell.Source.MANUAL  // recrawl path — not a fresh seed, not user-driven
            );
            if (ingestionQueue.offer(task)) enqueued++;
        }
        log.info("Daily recrawl: {}/{} stale cells enqueued (budget={}).",
            enqueued, stale.getNumberOfElements(), ingestionProperties.getDailyRecrawlBudget());
    }
}
