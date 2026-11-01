package com.foodmap.ingestion.snapshot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Coordinate and locality validation before a snapshot run saves a place. */
class SnapshotPlaceValidatorTest {

    static final List<SnapshotPlan.Circle> QUAN_1 = List.of(new SnapshotPlan.Circle(10.7737, 106.6999, 2500));

    @Test
    void aPlaceInsideTheRegionIsAccepted() {
        assertThat(SnapshotPlaceValidator.rejectReason(10.7760, 106.7010, QUAN_1)).isEmpty();
    }

    @Test
    void invalidCoordinatesAreRejected() {
        assertThat(SnapshotPlaceValidator.rejectReason(null, 106.7, QUAN_1)).contains("INVALID_COORDINATES");
        assertThat(SnapshotPlaceValidator.rejectReason(10.7, null, QUAN_1)).contains("INVALID_COORDINATES");
        assertThat(SnapshotPlaceValidator.rejectReason(Double.NaN, 106.7, QUAN_1)).contains("INVALID_COORDINATES");
        assertThat(SnapshotPlaceValidator.rejectReason(10.7, Double.POSITIVE_INFINITY, QUAN_1)).contains("INVALID_COORDINATES");
        assertThat(SnapshotPlaceValidator.rejectReason(91.0, 106.7, QUAN_1)).contains("INVALID_COORDINATES");
        assertThat(SnapshotPlaceValidator.rejectReason(10.7, 181.0, QUAN_1)).contains("INVALID_COORDINATES");
        assertThat(SnapshotPlaceValidator.rejectReason(0.0, 0.0, QUAN_1)).contains("INVALID_COORDINATES");
    }

    @Test
    void placesOutsideVietnamOrTheCityAreRejected() {
        assertThat(SnapshotPlaceValidator.rejectReason(1.3521, 103.8198, QUAN_1)).contains("OUTSIDE_VIETNAM");   // Singapore
        assertThat(SnapshotPlaceValidator.rejectReason(21.0285, 105.8542, QUAN_1)).contains("OUTSIDE_HCMC");     // Hà Nội
    }

    @Test
    void aCityPlaceOutsideTheTargetRegionIsRejected() {
        assertThat(SnapshotPlaceValidator.rejectReason(10.8385, 106.6630, QUAN_1)).contains("OUTSIDE_REGION");   // Gò Vấp
    }
}
