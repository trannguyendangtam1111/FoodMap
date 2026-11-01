package com.foodmap.restaurant.service;

import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.AppProperties;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.CellCoverageService;
import com.foodmap.ingestion.IngestionQueue;
import com.foodmap.ingestion.IngestionTask;
import com.foodmap.ingestion.ViewportEnqueueGate;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.food.match.FoodClassification;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.DiscoveryResult;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.ranking.PersonalizationContext;
import com.foodmap.restaurant.ranking.RankingService;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local-first discovery read path.
 *
 *   ╔══════════════════════════════════════════════════════════════════╗
 *   ║  The user-facing response is ALWAYS served from PostgreSQL.      ║
 *   ║  No provider call is ever on the critical path of a viewport     ║
 *   ║  or nearby request.                                              ║
 *   ╚══════════════════════════════════════════════════════════════════╝
 *
 * Each method is a two-step:
 *   1. Synchronously serve the request from PostgreSQL + PostGIS.
 *   2. Only with app.ingestion.viewport-enqueue-enabled=true (off by
 *      default — snapshot mode): asynchronously enqueue any geohash-6
 *      cells that haven't been ingested (or whose data has expired) into
 *      the IngestionQueue, protected by a per-cell ViewportEnqueueGate so
 *      a rapid map-pan cannot create burst enqueues for the same area.
 *
 * Each request also enforces {@link #MAX_ENQUEUE_PER_REQUEST} so a single
 * viewport at low zoom can't push hundreds of cells into the queue.
 *
 * The number of newly enqueued cells is returned to the controller so it
 * can surface it on a response header — the frontend uses it to show an
 * "indexing this area…" hint, signalling that revisiting will yield
 * richer results.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiscoveryService {

    /**
     * Hard cap on how many cells one request can drop into the ingestion
     * queue. Prevents a low-zoom (city-level) viewport from saturating the
     * queue with a single pan. Excess stale cells will be picked up by a
     * later, more-zoomed-in viewport or by the daily recrawl scheduler.
     */
    private static final int MAX_ENQUEUE_PER_REQUEST = 8;

    private final RestaurantRepository restaurantRepository;
    private final RestaurantMapper restaurantMapper;
    private final RankingService rankingService;
    private final FoodClassifier foodClassifier;
    private final CellCoverageService cellCoverageService;
    private final IngestionQueue ingestionQueue;
    private final ViewportEnqueueGate viewportEnqueueGate;
    private final AppProperties appProperties;
    private final IngestionProperties ingestionProperties;

    // ── Viewport (map pan) ────────────────────────────────────────────────────

    public DiscoveryResult<RestaurantSummaryDTO> getViewport(
            double swLat, double swLng, double neLat, double neLng) {

        double[] center = GeoUtils.viewportCenter(swLat, swLng, neLat, neLng);
        var candidates = restaurantRepository
            .findInEnvelope(swLat, swLng, neLat, neLng, appProperties.getViewportMaxResults());

        List<RestaurantSummaryDTO> items = summaries(rankingService
            .rank(candidates, center[0], center[1], PersonalizationContext.anonymous()));

        int enqueued = enqueueStaleCells(swLat, swLng, neLat, neLng, PlaceCell.Source.VIEWPORT);
        return new DiscoveryResult<>(items, enqueued);
    }

    // ── Nearby (radius around point) ─────────────────────────────────────────

    public DiscoveryResult<RestaurantSummaryDTO> getNearby(
            double lat, double lng, double radiusMeters) {

        double clampedRadius = Math.min(radiusMeters, appProperties.getNearbyMaxRadiusM());

        var candidates = restaurantRepository
            .findWithinRadius(lat, lng, clampedRadius, appProperties.getViewportMaxResults());

        List<RestaurantSummaryDTO> items = summaries(rankingService
            .rank(candidates, lat, lng, PersonalizationContext.anonymous()));

        double[] box = GeoUtils.boundingBox(lat, lng, clampedRadius);
        int enqueued = enqueueStaleCells(box[0], box[1], box[2], box[3], PlaceCell.Source.VIEWPORT);
        return new DiscoveryResult<>(items, enqueued);
    }

    // Search is not here: it is strictly read-only (restaurant.search.
    // RestaurantSearchService) and never triggers ingestion.

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Maps ranked rows with their food classification (one batched menu query). */
    private List<RestaurantSummaryDTO> summaries(List<Restaurant> ranked) {
        Map<Long, FoodClassification> food = foodClassifier.classifyAll(ranked);
        return ranked.stream().map(r -> restaurantMapper.toSummary(r, food.get(r.getId()))).toList();
    }

    /**
     * Resolve a bbox to its overlapping geohash-6 cells, filter through the
     * DB cooldown ledger ({@code staleOrUnseen}), then through the in-memory
     * ViewportEnqueueGate (short-window burst dampener), then enforce
     * {@link #MAX_ENQUEUE_PER_REQUEST}.
     *
     * Read-only transaction safe: this only writes to an in-memory queue,
     * not the DB.
     */
    private int enqueueStaleCells(double swLat, double swLng,
                                  double neLat, double neLng,
                                  PlaceCell.Source source) {
        // Snapshot mode (default): browsing reads PostgreSQL only — no Goong ingestion.
        if (!ingestionProperties.isViewportEnqueueEnabled()) return 0;
        Set<String> cellsInBbox = cellCoverageService.cellsInBbox(swLat, swLng, neLat, neLng);
        Set<String> needsIngestion = cellCoverageService.staleOrUnseen(cellsInBbox);
        if (needsIngestion.isEmpty()) return 0;

        int accepted = 0;
        for (String cellKey : needsIngestion) {
            if (accepted >= MAX_ENQUEUE_PER_REQUEST) {
                log.debug("Per-request enqueue cap ({}) reached — deferring remaining cells",
                    MAX_ENQUEUE_PER_REQUEST);
                break;
            }
            if (!viewportEnqueueGate.tryEnqueue(cellKey)) {
                continue;   // recently enqueued from a previous request — skip
            }
            double[] center = cellCoverageService.decodeCenter(cellKey);
            IngestionTask task = new IngestionTask(cellKey, center[0], center[1], source);
            if (ingestionQueue.offer(task)) {
                accepted++;
            }
        }
        if (accepted > 0) {
            log.debug("enqueued {}/{} stale cells from bbox ({}, {})-({}, {})",
                accepted, needsIngestion.size(), swLat, swLng, neLat, neLng);
        }
        return accepted;
    }
}
