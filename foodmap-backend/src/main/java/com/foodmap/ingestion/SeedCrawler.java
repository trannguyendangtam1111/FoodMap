package com.foodmap.ingestion;

import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.domain.PlaceCell;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-populates the discovery DB with restaurants from across Ho Chi Minh
 * City so the app feels populated from the very first user request.
 *
 * STRATEGY
 *   - A short list of district anchor points (Q1, Q3, Q5, Q7, Q10, Bình
 *     Thạnh, Phú Nhuận, Gò Vấp, Thủ Đức, Q2).
 *   - Around each anchor, fan out a small 3x3 grid at ~0.01° (~1.1km)
 *     spacing. Each grid point resolves to one geohash-6 cell.
 *   - Dedupe (overlapping anchors share cells) → ~70 unique cells.
 *   - At ingestion rate ~10 rps (Goong limit), this seeds the whole city
 *     in roughly a minute.
 *
 * IDEMPOTENT
 *   On every startup we check place_cells.last_indexed_at; only cells
 *   that are unseen or stale are enqueued. Restarting the app a hundred
 *   times costs zero Goong calls after the first successful seed.
 *
 * GATING
 *   Controlled by app.ingestion.seed-on-startup — OFF by default (snapshot
 *   mode: a restart never spends Goong quota). Set
 *   INGESTION_SEED_ON_STARTUP=true for one deliberate seed run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedCrawler {

    /**
     * Anchor points roughly at the commercial heart of each major HCMC
     * district. Lists here intentionally — readable, easy to tweak, and
     * we don't want to pull this from config until there's a real reason.
     */
    private static final List<double[]> HCMC_ANCHORS = List.of(
        new double[]{10.7720, 106.6981}, // Q1 — Bến Thành area
        new double[]{10.7820, 106.6889}, // Q3 — Hồ Con Rùa
        new double[]{10.7560, 106.6610}, // Q5 — Chợ Lớn
        new double[]{10.7720, 106.6680}, // Q10 — Sư Vạn Hạnh
        new double[]{10.7950, 106.6810}, // Phú Nhuận
        new double[]{10.8050, 106.7000}, // Bình Thạnh — Đinh Tiên Hoàng
        new double[]{10.8350, 106.6650}, // Gò Vấp
        new double[]{10.8300, 106.7680}, // Thủ Đức — Đặng Văn Bi
        new double[]{10.7290, 106.7160}, // Q7 — Phú Mỹ Hưng
        new double[]{10.8060, 106.7390}, // Q2 — Thảo Điền
        new double[]{10.7625, 106.6820}, // Q4 — Bến Vân Đồn
        new double[]{10.7460, 106.6300}  // Q6 — Phú Lâm
    );

    /** 3x3 grid spacing — slightly under one cell width at HCMC latitude. */
    private static final double GRID_STEP_DEG = 0.010;

    private final IngestionQueue ingestionQueue;
    private final CellCoverageService cellCoverageService;
    private final IngestionProperties properties;

    /**
     * Fired once after the app context is fully initialised (after Flyway,
     * after the data seeder, after the worker pool is up). Using
     * ApplicationReadyEvent instead of @PostConstruct guarantees the
     * IngestionWorker threads are alive when we start producing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!properties.isSeedOnStartup()) {
            log.info("SeedCrawler: seed-on-startup disabled — skipping.");
            return;
        }
        Set<String> cells = computeSeedCells();
        Set<String> needsIngestion = cellCoverageService.staleOrUnseen(cells);
        if (needsIngestion.isEmpty()) {
            log.info("SeedCrawler: all {} seed cells already fresh — nothing to do.", cells.size());
            return;
        }
        log.info("SeedCrawler: enqueueing {}/{} seed cells (the rest are still fresh).",
            needsIngestion.size(), cells.size());
        int enqueued = 0;
        for (String cellKey : needsIngestion) {
            double[] center = cellCoverageService.decodeCenter(cellKey);
            IngestionTask task = new IngestionTask(cellKey, center[0], center[1], PlaceCell.Source.SEED);
            if (ingestionQueue.offer(task)) enqueued++;
        }
        log.info("SeedCrawler: {} cells accepted into the ingestion queue.", enqueued);
    }

    private Set<String> computeSeedCells() {
        Set<String> cells = new LinkedHashSet<>();
        for (double[] anchor : HCMC_ANCHORS) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    double lat = anchor[0] + dy * GRID_STEP_DEG;
                    double lng = anchor[1] + dx * GRID_STEP_DEG;
                    cells.add(GeoUtils.geohash(lat, lng, CellCoverageService.CELL_PRECISION));
                }
            }
        }
        return cells;
    }
}
