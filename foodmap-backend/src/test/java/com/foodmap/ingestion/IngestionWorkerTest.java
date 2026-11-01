package com.foodmap.ingestion;

import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.service.RestaurantSyncService;
import com.foodmap.restaurant.service.RestaurantSyncService.PlaceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Worker-level tests: only resolved restaurants inside the crawl radius may
 * count toward a cell's coverage (place_count) and therefore its cooldown.
 *
 * Collaborators are mocked except CellCoverageService (real, over a mocked
 * repository) and IngestionMetrics (real), so the saved place_cells row and
 * the outcome bucket reflect production logic end to end.
 */
class IngestionWorkerTest {

    private static final double CENTER_LAT = 10.7769;
    private static final double CENTER_LNG = 106.7009;
    private static final double M_PER_DEG_LAT = 6_371_000 * Math.PI / 180;

    private IngestionQueue queue;
    private RestaurantSyncService restaurantSyncService;
    private KeywordFanoutService keywordFanoutService;
    private PlaceCellRepository placeCellRepository;
    private IngestionMetrics metrics;
    private IngestionWorker worker;

    @BeforeEach
    void setUp() {
        queue = mock(IngestionQueue.class);
        restaurantSyncService = mock(RestaurantSyncService.class);
        keywordFanoutService = mock(KeywordFanoutService.class);
        placeCellRepository = mock(PlaceCellRepository.class);
        when(placeCellRepository.findById(anyString())).thenReturn(Optional.empty());

        IngestionProperties properties = new IngestionProperties();
        CellCoverageService cellCoverageService = new CellCoverageService(placeCellRepository, properties);
        metrics = new IngestionMetrics();
        worker = new IngestionWorker(queue, restaurantSyncService, keywordFanoutService,
            cellCoverageService, properties, metrics);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static IngestionTask task(String cellKey, double lat) {
        return new IngestionTask(cellKey, lat, CENTER_LNG, PlaceCell.Source.VIEWPORT);
    }

    private static Restaurant restaurantNorthOf(double lat, double meters) {
        return Restaurant.builder()
            .latitude(lat + meters / M_PER_DEG_LAT)
            .longitude(CENTER_LNG)
            .build();
    }

    /** Fan-out returns the given ids from two successful keywords. */
    private void fanoutReturns(Set<String> placeIds) {
        when(keywordFanoutService.discoverPlaceIds(anyDouble(), anyDouble(), anyInt()))
            .thenReturn(new FanoutResult(placeIds, 2, 0));
    }

    private static Set<String> ids(int count) {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 1; i <= count; i++) ids.add("place-" + i);
        return ids;
    }

    private PlaceCell savedCell() {
        ArgumentCaptor<PlaceCell> saved = ArgumentCaptor.forClass(PlaceCell.class);
        verify(placeCellRepository).save(saved.capture());
        return saved.getValue();
    }

    private static void assertCooldown(PlaceCell cell, Duration expected) {
        long secondsFromNow = Duration.between(Instant.now(), cell.getNextEligibleAt()).getSeconds();
        assertThat(secondsFromNow).isCloseTo(expected.getSeconds(), within(60L));
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void freshCachedRestaurantInsideRadius_countsAsLocal_withoutPlaceDetail() {
        fanoutReturns(ids(1));
        when(restaurantSyncService.ensureFreshPlace("place-1"))
            .thenReturn(PlaceSyncResult.cacheHit(restaurantNorthOf(CENTER_LAT, 200)));

        worker.processTask(task("w3gv7e", CENTER_LAT));

        assertThat(savedCell().getPlaceCount()).isEqualTo(1);
        assertThat(metrics.snapshot().getPlaceDetailCalls()).isZero();
        verify(queue).markCompleted("w3gv7e");
    }

    @Test
    void mixedResults_onlyLocalRestaurantsCountTowardCoverage() {
        fanoutReturns(ids(10));
        double[] metersAway = {100, 250, 480, 1_200, 3_000, 5_000, 8_000, 12_000, 16_000, 20_000};
        for (int i = 0; i < metersAway.length; i++) {
            Restaurant r = restaurantNorthOf(CENTER_LAT, metersAway[i]);
            // Mix cache hits and Place Detail fetches — both carry coordinates.
            PlaceSyncResult result = (i % 2 == 0)
                ? PlaceSyncResult.cacheHit(r)
                : PlaceSyncResult.fetched(Optional.of(r));
            when(restaurantSyncService.ensureFreshPlace("place-" + (i + 1))).thenReturn(result);
        }

        worker.processTask(task("w3gv7e", CENTER_LAT));

        PlaceCell cell = savedCell();
        assertThat(cell.getPlaceCount()).isEqualTo(3);
        assertThat(cell.getStatus()).isEqualTo(PlaceCell.Status.READY);
        assertCooldown(cell, Duration.ofDays(30));
        assertThat(metrics.snapshot().getCrawlsSucceeded()).isEqualTo(1);
        // Distant restaurants are still resolved/synced — they just don't cover this cell.
        assertThat(metrics.snapshot().getPlacesUpserted()).isEqualTo(10);
        verify(restaurantSyncService, times(10)).ensureFreshPlace(anyString());
    }

    @Test
    void noLocalResults_isLowDensity_notThirtyDayFresh() {
        fanoutReturns(ids(10));
        for (int i = 1; i <= 10; i++) {
            when(restaurantSyncService.ensureFreshPlace("place-" + i))
                .thenReturn(PlaceSyncResult.cacheHit(restaurantNorthOf(CENTER_LAT, 2_000 * i)));
        }

        worker.processTask(task("w3gv7e", CENTER_LAT));

        PlaceCell cell = savedCell();
        assertThat(cell.getPlaceCount()).isZero();
        assertThat(cell.getStatus()).isEqualTo(PlaceCell.Status.READY);
        assertCooldown(cell, Duration.ofHours(6));
        assertThat(metrics.snapshot().getCrawlsLowDensity()).isEqualTo(1);
        assertThat(metrics.snapshot().getCrawlsSucceeded()).isZero();
    }

    @Test
    void restaurantWithoutCoordinates_isResolvedButNotLocal() {
        fanoutReturns(ids(1));
        Restaurant noCoords = Restaurant.builder().build();
        when(restaurantSyncService.ensureFreshPlace("place-1"))
            .thenReturn(PlaceSyncResult.cacheHit(noCoords));

        worker.processTask(task("w3gv7e", CENTER_LAT));

        assertThat(savedCell().getPlaceCount()).isZero();
        assertThat(metrics.snapshot().getPlacesUpserted()).isEqualTo(1);
    }

    @Test
    void allKeywordsFailed_stillRecordsCrawlFailure() {
        when(keywordFanoutService.discoverPlaceIds(anyDouble(), anyDouble(), anyInt()))
            .thenReturn(new FanoutResult(Set.of(), 2, 2));

        worker.processTask(task("w3gv7e", CENTER_LAT));

        PlaceCell cell = savedCell();
        assertThat(cell.getAttemptCount()).isEqualTo(1);
        assertThat(cell.getLastError()).isEqualTo("fanout_all_failed");
        assertCooldown(cell, Duration.ofMinutes(2));
        assertThat(metrics.snapshot().getCrawlsFailed()).isEqualTo(1);
        assertThat(metrics.snapshot().getCrawlsLowDensity()).isZero();
        verify(restaurantSyncService, never()).ensureFreshPlace(anyString());
    }

    @Test
    void sameRestaurant_canCoverTwoAdjacentCells() {
        // North-south geohash-6 neighbours are ~611 m apart; a restaurant
        // between them is within 500 m of both centers. No global de-duplication.
        double cellHeightDeg = 180.0 / 32768;
        double northCenterLat = CENTER_LAT + cellHeightDeg;
        Restaurant between = Restaurant.builder()
            .latitude(CENTER_LAT + cellHeightDeg / 2)
            .longitude(CENTER_LNG)
            .build();
        fanoutReturns(ids(1));
        when(restaurantSyncService.ensureFreshPlace("place-1"))
            .thenReturn(PlaceSyncResult.cacheHit(between));

        worker.processTask(task("w3gv7e", CENTER_LAT));
        worker.processTask(task("w3gv7s", northCenterLat));

        ArgumentCaptor<PlaceCell> saved = ArgumentCaptor.forClass(PlaceCell.class);
        verify(placeCellRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PlaceCell::getPlaceCount).containsExactly(1, 1);
    }
}
