package com.foodmap.ingestion.snapshot;

import com.foodmap.common.util.GeoUtils;
import com.foodmap.integration.goong.dto.GoongPlaceDTO;

import java.util.List;
import java.util.Optional;

/**
 * Whether a fetched place may be saved by a snapshot run. Runs after the
 * existing validity rule (place_id, name, coordinates present); a place with
 * a reason here is NOT saved and its place_id is recorded as rejected.
 *
 *   INVALID_COORDINATES  not finite, out of range, or 0,0
 *   OUTSIDE_VIETNAM      outside Vietnam's bounding box
 *   OUTSIDE_HCMC         outside Hồ Chí Minh City's bounding box
 *   OUTSIDE_REGION       outside every circle of the run's target region
 */
public final class SnapshotPlaceValidator {

    // Generous bounding boxes — sanity checks, not borders.
    static final double VN_MIN_LAT = 8.0, VN_MAX_LAT = 23.5, VN_MIN_LNG = 102.0, VN_MAX_LNG = 110.0;
    static final double HCMC_MIN_LAT = 10.3, HCMC_MAX_LAT = 11.2, HCMC_MIN_LNG = 106.3, HCMC_MAX_LNG = 107.1;

    private SnapshotPlaceValidator() {}

    public static Optional<String> rejectReason(GoongPlaceDTO place, List<SnapshotPlan.Circle> region) {
        Double lat = place.geometry() == null || place.geometry().location() == null ? null : place.geometry().location().lat();
        Double lng = place.geometry() == null || place.geometry().location() == null ? null : place.geometry().location().lng();
        return rejectReason(lat, lng, region);
    }

    public static Optional<String> rejectReason(Double lat, Double lng, List<SnapshotPlan.Circle> region) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                || Math.abs(lat) > 90 || Math.abs(lng) > 180 || (lat == 0.0 && lng == 0.0)) {
            return Optional.of("INVALID_COORDINATES");
        }
        if (lat < VN_MIN_LAT || lat > VN_MAX_LAT || lng < VN_MIN_LNG || lng > VN_MAX_LNG) {
            return Optional.of("OUTSIDE_VIETNAM");
        }
        if (lat < HCMC_MIN_LAT || lat > HCMC_MAX_LAT || lng < HCMC_MIN_LNG || lng > HCMC_MAX_LNG) {
            return Optional.of("OUTSIDE_HCMC");
        }
        boolean inRegion = region.stream()
            .anyMatch(c -> GeoUtils.distanceMeters(c.lat(), c.lng(), lat, lng) <= c.radiusMeters());
        return inRegion ? Optional.empty() : Optional.of("OUTSIDE_REGION");
    }
}
