package com.foodmap.ingestion.snapshot;

import com.foodmap.ingestion.CellCoverageService;

import java.util.List;

/**
 * What one snapshot run will do: the discovery units (in order), the
 * Autocomplete calls they need, and the region a new restaurant must lie in
 * to be saved.
 *
 * A unit is either a geohash-6 map cell (the existing keywords at the cell
 * centre, 500 m) or a food probe (one food's catalog label at an area
 * preset's centre, within the area radius). Both go through the same budget,
 * dedupe, Place Detail, validation and report; only map cells are recorded in
 * place_cells.
 *
 * @param cells           planned units, in processing order
 * @param region          circles; a fetched place is saved only inside one of them
 * @param discoveryCalls  Autocomplete calls planned (the discovery phase's hard cap)
 * @param skipped         candidates left out (and why), for the report
 */
public record SnapshotPlan(List<Cell> cells, List<Circle> region, int discoveryCalls, List<String> skipped) {

    public enum Priority { NEVER_CRAWLED, SPARSE_OR_STALE, CRAWLED, RESUMED, FOOD_PROBE }

    /**
     * @param cellKey          geohash-6 key, or "food:{foodKey}@{areaKey}" for a food probe
     * @param keywords         Autocomplete inputs still to run for this unit
     * @param pendingPlaceIds  place_ids already discovered but not resolved (resume)
     * @param localSoFar       local restaurants already resolved for this unit by an earlier run (resume)
     * @param radiusMeters     the Autocomplete radius around (lat, lng)
     * @param foodKey          the probed food (food probes only), else null
     * @param areaKey          the area preset (food probes only), else null
     */
    public record Cell(String cellKey, double lat, double lng, Priority priority,
                       List<String> keywords, List<PendingPlace> pendingPlaceIds, int localSoFar,
                       int radiusMeters, String foodKey, String areaKey) {

        /** A geohash-6 map cell (the existing discovery unit). */
        public static Cell mapCell(String cellKey, double lat, double lng, Priority priority,
                                   List<String> keywords, List<PendingPlace> pendingPlaceIds, int localSoFar) {
            return new Cell(cellKey, lat, lng, priority, keywords, pendingPlaceIds, localSoFar,
                CellCoverageService.CELL_RADIUS_METERS, null, null);
        }

        public boolean isFoodProbe() {
            return foodKey != null;
        }
    }

    /** A discovered place still waiting for Place Detail; texts kept for name+address matching. */
    public record PendingPlace(String placeId, String mainText, String secondaryText) {}

    public record Circle(double lat, double lng, double radiusMeters) {}

    public static String foodProbeKey(String foodKey, String areaKey) {
        return "food:" + foodKey + "@" + areaKey;
    }
}
