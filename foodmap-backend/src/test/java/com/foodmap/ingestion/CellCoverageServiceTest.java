package com.foodmap.ingestion;

import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the crawl-radius locality check and the cooldown it feeds.
 *
 * Points are offset due north of the cell center: along a meridian the
 * haversine distance used by GeoUtils is exactly R·Δφ, so a north offset of
 * d metres measures as d metres without floating-point drift.
 */
class CellCoverageServiceTest {

    private static final double CENTER_LAT = 10.7769;
    private static final double CENTER_LNG = 106.7009;

    /** Metres per degree of latitude on GeoUtils' 6371 km sphere. */
    private static final double M_PER_DEG_LAT = 6_371_000 * Math.PI / 180;

    /** Height of one geohash-6 cell in degrees of latitude (180 / 2^15). */
    private static final double CELL_HEIGHT_DEG = 180.0 / 32768;

    private PlaceCellRepository placeCellRepository;
    private CellCoverageService service;

    @BeforeEach
    void setUp() {
        placeCellRepository = mock(PlaceCellRepository.class);
        when(placeCellRepository.findById(anyString())).thenReturn(Optional.empty());
        service = new CellCoverageService(placeCellRepository, new IngestionProperties());
    }

    private static double latNorthOf(double lat, double meters) {
        return lat + meters / M_PER_DEG_LAT;
    }

    private boolean isLocal(double metersNorth) {
        return service.isWithinCrawlRadius(
            CENTER_LAT, CENTER_LNG, latNorthOf(CENTER_LAT, metersNorth), CENTER_LNG);
    }

    // ── isWithinCrawlRadius ──────────────────────────────────────────────────

    @Test
    void placeAbout200mAway_isLocal() {
        assertThat(isLocal(200)).isTrue();
    }

    @Test
    void boundary_isInclusiveAt500m() {
        assertThat(isLocal(499.9)).isTrue();
        assertThat(isLocal(500.1)).isFalse();
    }

    @Test
    void placesClearlyOutsideTheRadius_areNotLocal() {
        assertThat(isLocal(600)).isFalse();
        assertThat(isLocal(20_000)).isFalse();
    }

    @Test
    void missingOrInvalidCoordinates_areNotLocal_andDoNotThrow() {
        assertThat(service.isWithinCrawlRadius(CENTER_LAT, CENTER_LNG, null, CENTER_LNG)).isFalse();
        assertThat(service.isWithinCrawlRadius(CENTER_LAT, CENTER_LNG, CENTER_LAT, null)).isFalse();
        assertThat(service.isWithinCrawlRadius(CENTER_LAT, CENTER_LNG, null, null)).isFalse();
        assertThat(service.isWithinCrawlRadius(CENTER_LAT, CENTER_LNG, Double.NaN, CENTER_LNG)).isFalse();
    }

    @Test
    void restaurantBetweenTwoNorthSouthNeighbours_isLocalToBoth() {
        // Adjacent geohash-6 cells are ~611 m apart north-south, so their
        // 500 m circles overlap. A place in the overlap legitimately covers both.
        double northCenterLat = CENTER_LAT + CELL_HEIGHT_DEG;
        double midpointLat = CENTER_LAT + CELL_HEIGHT_DEG / 2;

        assertThat(service.isWithinCrawlRadius(CENTER_LAT, CENTER_LNG, midpointLat, CENTER_LNG)).isTrue();
        assertThat(service.isWithinCrawlRadius(northCenterLat, CENTER_LNG, midpointLat, CENTER_LNG)).isTrue();
    }

    // ── recordCrawlSuccess cooldown (durations unchanged; input is the local count) ──

    private PlaceCell recordSuccessAndCapture(int localPlaceCount) {
        service.recordCrawlSuccess("w3gv7e", CENTER_LAT, CENTER_LNG, PlaceCell.Source.VIEWPORT, localPlaceCount);
        ArgumentCaptor<PlaceCell> saved = ArgumentCaptor.forClass(PlaceCell.class);
        verify(placeCellRepository).save(saved.capture());
        return saved.getValue();
    }

    private static void assertCooldown(PlaceCell cell, Duration expected) {
        long secondsFromNow = Duration.between(Instant.now(), cell.getNextEligibleAt()).getSeconds();
        assertThat(secondsFromNow).isCloseTo(expected.getSeconds(), within(60L));
    }

    @Test
    void zeroLocalPlaces_getsLowDensityCooldown() {
        PlaceCell cell = recordSuccessAndCapture(0);
        assertThat(cell.getPlaceCount()).isZero();
        assertThat(cell.getStatus()).isEqualTo(PlaceCell.Status.READY);
        assertCooldown(cell, Duration.ofHours(6));
    }

    @Test
    void oneLocalPlace_getsLowDensityCooldown() {
        assertCooldown(recordSuccessAndCapture(1), Duration.ofHours(6));
    }

    @Test
    void twoLocalPlaces_getLowDensityCooldown() {
        assertCooldown(recordSuccessAndCapture(2), Duration.ofHours(6));
    }

    @Test
    void threeLocalPlaces_getFullFreshCooldown() {
        PlaceCell cell = recordSuccessAndCapture(3);
        assertThat(cell.getPlaceCount()).isEqualTo(3);
        assertThat(cell.getStatus()).isEqualTo(PlaceCell.Status.READY);
        assertCooldown(cell, Duration.ofDays(30));
    }

    @Test
    void crawlFailure_keepsRetryBackoffSemantics() {
        service.recordCrawlFailure("w3gv7e", CENTER_LAT, CENTER_LNG, PlaceCell.Source.VIEWPORT, "fanout_all_failed");
        ArgumentCaptor<PlaceCell> saved = ArgumentCaptor.forClass(PlaceCell.class);
        verify(placeCellRepository).save(saved.capture());
        PlaceCell cell = saved.getValue();

        assertThat(cell.getAttemptCount()).isEqualTo(1);
        assertThat(cell.getLastError()).isEqualTo("fanout_all_failed");
        assertThat(cell.getStatus()).isEqualTo(PlaceCell.Status.READY);
        assertCooldown(cell, Duration.ofMinutes(2));
        verify(placeCellRepository).findById(any());
    }
}
