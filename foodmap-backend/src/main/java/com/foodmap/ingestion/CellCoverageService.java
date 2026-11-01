package com.foodmap.ingestion;

import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for everything geohash-6 in this app.
 *
 * Two responsibilities:
 *   1. Bbox → set of cell keys that overlap the viewport.
 *   2. Cell key → center lat/lng (decoder), used by the IngestionWorker
 *      to drive a Goong nearby call without re-routing through GeoUtils.
 *
 * The freshness window (how old a cell must be before we recrawl) lives
 * here too so DiscoveryService and the scheduler agree on the same TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CellCoverageService {

    /**
     * Precision-6 geohash. At HCMC's latitude (~10.78°) a cell is
     * roughly 1.2km × 0.6km — small enough to fit several inside one
     * map viewport, large enough that Goong's nearbysearch returns
     * useful coverage in one call.
     */
    public static final int CELL_PRECISION = 6;

    /**
     * Provider call radius. Deliberately set BELOW the cell's half-diagonal
     * (~670m at HCMC latitude) so neighbouring cells do NOT overlap when
     * queried. 500m gives effective coverage of the cell interior while
     * letting the *next* cell pick up its own edge — duplicate discovery
     * from neighbour overlap was the dominant source of wasted Goong calls.
     *
     * Also the coverage radius: only places within this distance of the
     * cell center count toward place_count (see {@link #isWithinCrawlRadius}).
     */
    public static final int CELL_RADIUS_METERS = 500;

    /**
     * Historical constant kept for callers that still reference it for
     * doc / log purposes. The actual freshness window is now per-cell
     * and lives on PlaceCell.nextEligibleAt — populated by
     * recordCrawlSuccess / recordCrawlFailure below using configurable
     * values from IngestionProperties.
     */
    public static final int CELL_FRESHNESS_DAYS = 30;

    /**
     * Coarse step (in degrees) for the bbox enumeration. Must be ≤ the
     * cell dimensions — at precision 6 the smallest cell side at the
     * equator is ~0.0055°, so 0.003° (~330m) is safely below that and
     * works at any sensible latitude.
     */
    private static final double ENUM_STEP_DEG = 0.003;

    /**
     * Defensive cap — if a viewport is enormous (low zoom level) we
     * don't want to enumerate thousands of cells. Caller (DiscoveryService)
     * should also clamp the bbox upstream, but this is the safety net.
     */
    private static final int MAX_CELLS_PER_BBOX = 64;

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private final PlaceCellRepository placeCellRepository;
    private final IngestionProperties ingestionProperties;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Return every distinct geohash-6 cell whose footprint overlaps the bbox.
     *
     * We scan a regular lat/lng grid at ENUM_STEP_DEG and dedupe into a
     * LinkedHashSet (preserves insertion order — handy for logging). For the
     * viewport sizes we actually serve (zoom 12–17, single-city), this is
     * a handful of operations.
     */
    public Set<String> cellsInBbox(double swLat, double swLng, double neLat, double neLng) {
        Set<String> cells = new LinkedHashSet<>();

        for (double lat = swLat; lat <= neLat + ENUM_STEP_DEG / 2; lat += ENUM_STEP_DEG) {
            for (double lng = swLng; lng <= neLng + ENUM_STEP_DEG / 2; lng += ENUM_STEP_DEG) {
                cells.add(GeoUtils.geohash(lat, lng, CELL_PRECISION));
                if (cells.size() >= MAX_CELLS_PER_BBOX) {
                    log.debug("Bbox enumeration hit MAX_CELLS_PER_BBOX={} — clamping", MAX_CELLS_PER_BBOX);
                    return cells;
                }
            }
        }
        // Always include the four corners exactly — the step loop can miss
        // them when the bbox dimensions aren't an integer multiple of the step.
        cells.add(GeoUtils.geohash(swLat, swLng, CELL_PRECISION));
        cells.add(GeoUtils.geohash(swLat, neLng, CELL_PRECISION));
        cells.add(GeoUtils.geohash(neLat, swLng, CELL_PRECISION));
        cells.add(GeoUtils.geohash(neLat, neLng, CELL_PRECISION));

        return cells;
    }

    /**
     * From a set of requested cells, return only those eligible for
     * ingestion right now — i.e. never crawled, OR past their cooldown
     * (next_eligible_at ≤ now).
     *
     * Implementation: one indexed probe to PlaceCellRepository, then a
     * set-difference in Java. Keeps the read path's DB cost flat at
     * one round trip per viewport.
     *
     * The method name predates the cooldown model and is kept for
     * caller stability; "fresh" inside the body means "still cooling
     * down", not "recently indexed".
     */
    public Set<String> staleOrUnseen(Set<String> requested) {
        if (requested.isEmpty()) return Set.of();
        Set<String> coolingDown = placeCellRepository.findFreshKeys(requested, Instant.now());
        Set<String> needsIngestion = new HashSet<>(requested);
        needsIngestion.removeAll(coolingDown);
        return needsIngestion;
    }

    /**
     * True if a place lies within {@link #CELL_RADIUS_METERS} of a cell
     * center (inclusive). Missing or non-finite place coordinates are never
     * local. AutoComplete results are not guaranteed to lie near the cell,
     * so a resolved place is not evidence of coverage until it passes this
     * check.
     */
    public boolean isWithinCrawlRadius(double centerLat, double centerLng,
                                       Double placeLat, Double placeLng) {
        if (placeLat == null || placeLng == null
                || !Double.isFinite(placeLat) || !Double.isFinite(placeLng)) {
            return false;
        }
        return GeoUtils.distanceMeters(centerLat, centerLng, placeLat, placeLng) <= CELL_RADIUS_METERS;
    }

    /**
     * Record a successful crawl. Resets the retry counter, sets the next
     * cooldown based on whether the area looks well-populated or sparse,
     * and clears any prior error state.
     *
     * @param placeCount number of resolved places within the cell's crawl
     *                   radius (stored as place_count; drives the low-density
     *                   branch when below the configured threshold)
     */
    @Transactional
    public void recordCrawlSuccess(String cellKey, double centerLat, double centerLng,
                                   PlaceCell.Source source, int placeCount) {
        PlaceCell cell = loadOrCreate(cellKey, centerLat, centerLng, source);
        Instant now = Instant.now();

        Duration cooldown = (placeCount < ingestionProperties.getLowDensityThreshold())
            ? Duration.ofHours(ingestionProperties.getLowDensityCooldownHours())
            : Duration.ofDays(ingestionProperties.getFreshCooldownDays());

        cell.setLastIndexedAt(now);
        cell.setLastAttemptedAt(now);
        cell.setAttemptCount(0);
        cell.setPlaceCount(placeCount);
        cell.setStatus(PlaceCell.Status.READY);
        cell.setLastError(null);
        cell.setNextEligibleAt(now.plus(cooldown));
        placeCellRepository.save(cell);
    }

    /**
     * Record a failed crawl. Increments the attempt counter, applies an
     * exponential backoff (capped), and flips the cell to FAILED status
     * once the retry budget is exhausted (long cooldown applied so the
     * area gets a chance to come back later).
     *
     * Does NOT touch last_indexed_at — that field still reflects the
     * timestamp of the last *successful* crawl, so the discovery layer
     * and operators can distinguish "data is stale" from "we keep failing".
     */
    @Transactional
    public void recordCrawlFailure(String cellKey, double centerLat, double centerLng,
                                   PlaceCell.Source source, String reason) {
        PlaceCell cell = loadOrCreate(cellKey, centerLat, centerLng, source);
        Instant now = Instant.now();
        int attempts = cell.getAttemptCount() + 1;
        cell.setAttemptCount(attempts);
        cell.setLastAttemptedAt(now);
        cell.setLastError(truncate(reason, 255));

        if (attempts >= ingestionProperties.getMaxRetryAttempts()) {
            cell.setStatus(PlaceCell.Status.FAILED);
            cell.setNextEligibleAt(now.plus(Duration.ofHours(ingestionProperties.getFailedCooldownHours())));
            log.warn("Cell {} marked FAILED after {} attempts (reason='{}') — cooldown {}h",
                cellKey, attempts, reason, ingestionProperties.getFailedCooldownHours());
        } else {
            long backoffMin = (long) (ingestionProperties.getRetryBaseBackoffMinutes()
                                       * Math.pow(2.0, attempts - 1));
            backoffMin = Math.min(backoffMin, ingestionProperties.getRetryMaxBackoffMinutes());
            cell.setStatus(PlaceCell.Status.READY);
            cell.setNextEligibleAt(now.plus(Duration.ofMinutes(backoffMin)));
            log.info("Cell {} crawl attempt {} failed (reason='{}') — retry in {}min",
                cellKey, attempts, reason, backoffMin);
        }
        placeCellRepository.save(cell);
    }

    /**
     * Idempotent load-or-stub helper. Does NOT overwrite the source on
     * existing rows — a cell first seeded by SeedCrawler stays SEED
     * forever for telemetry purposes, even when later refreshed via
     * VIEWPORT/MANUAL paths.
     */
    private PlaceCell loadOrCreate(String cellKey, double centerLat, double centerLng,
                                    PlaceCell.Source source) {
        return placeCellRepository.findById(cellKey)
            .orElseGet(() -> PlaceCell.builder()
                .cellKey(cellKey)
                .centerLat(centerLat)
                .centerLng(centerLng)
                .source(source)
                .lastIndexedAt(Instant.EPOCH)  // overwritten by recordCrawlSuccess
                .build());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Decode a precision-6 geohash key back into the center of its cell.
     * Used by the IngestionWorker to call Goong's nearby endpoint.
     *
     * Inverse of GeoUtils.geohash — same base32 alphabet, same bit
     * interleaving (lng-first, alternating).
     */
    public double[] decodeCenter(String cellKey) {
        double[] latRange = { -90.0,  90.0 };
        double[] lngRange = {-180.0, 180.0};
        boolean isEven = true;

        for (int i = 0; i < cellKey.length(); i++) {
            int idx = BASE32.indexOf(cellKey.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("Invalid geohash character at position " + i + ": " + cellKey);
            }
            for (int b = 4; b >= 0; b--) {
                int bit = (idx >> b) & 1;
                if (isEven) {
                    double mid = (lngRange[0] + lngRange[1]) / 2;
                    if (bit == 1) lngRange[0] = mid; else lngRange[1] = mid;
                } else {
                    double mid = (latRange[0] + latRange[1]) / 2;
                    if (bit == 1) latRange[0] = mid; else latRange[1] = mid;
                }
                isEven = !isEven;
            }
        }
        return new double[] {
            (latRange[0] + latRange[1]) / 2,
            (lngRange[0] + lngRange[1]) / 2
        };
    }
}
