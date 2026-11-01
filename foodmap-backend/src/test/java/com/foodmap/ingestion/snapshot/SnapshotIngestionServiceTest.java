package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.food.match.FoodMatcher;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.common.exception.GoongRunAbortedException;
import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.AppProperties;
import com.foodmap.config.properties.GoongProperties;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.CellCoverageService;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import com.foodmap.ingestion.snapshot.SnapshotRunReport.StopReason;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.GoongRequestBudget;
import com.foodmap.integration.goong.dto.GoongGeometry;
import com.foodmap.integration.goong.dto.GoongLocation;
import com.foodmap.integration.goong.dto.GoongPlaceDTO;
import com.foodmap.integration.goong.dto.GoongPrediction;
import com.foodmap.integration.goong.mapper.GoongPlaceMapper;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.KnownPlaceRow;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.restaurant.service.RestaurantSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The snapshot run end to end with a fake Goong (it charges the real budget
 * exactly as GoongClientImpl does, per request) and mocked repositories.
 * No real Goong request, no database.
 */
class SnapshotIngestionServiceTest {

    // Two central HCMC cells (Quận 1, Quận 3).
    static final String CELL_A = GeoUtils.geohash(10.7769, 106.7009, 6);
    static final String CELL_B = GeoUtils.geohash(10.7820, 106.6889, 6);

    @TempDir Path reports;

    final GoongRequestBudget budget = new GoongRequestBudget();
    final FakeGoong goong = new FakeGoong(budget);
    final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    final PlaceCellRepository placeCells = mock(PlaceCellRepository.class);
    final IngestionProperties ingestion = new IngestionProperties();
    final CellCoverageService coverage = new CellCoverageService(placeCells, ingestion);
    final List<KnownPlaceRow> known = new ArrayList<>();
    final List<Restaurant> saved = new ArrayList<>();
    boolean lockFree = true;
    int locksTaken;

    double[] centerA;
    double[] centerB;

    @BeforeEach
    void setUp() {
        centerA = coverage.decodeCenter(CELL_A);
        centerB = coverage.decodeCenter(CELL_B);
        when(restaurants.findKnownPlaces()).thenAnswer(inv -> known);
        when(restaurants.findByGoongPlaceId(anyString())).thenAnswer(inv -> known.stream()
            .filter(k -> k.getGoongPlaceId().equals(inv.getArgument(0)))
            .findFirst()
            .map(k -> Restaurant.builder().id(k.getId()).goongPlaceId(k.getGoongPlaceId()).name(k.getName())
                .latitude(k.getLatitude()).longitude(k.getLongitude()).build()));
        when(restaurants.findByDedupFingerprint(anyString())).thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(1000);
        when(restaurants.save(any(Restaurant.class))).thenAnswer(inv -> {
            Restaurant r = inv.getArgument(0);
            r.setId(ids.incrementAndGet());
            saved.add(r);
            return r;
        });
        when(placeCells.save(any(PlaceCell.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** The server-owned catalog, as in production (food probes and classification of new places). */
    static final FoodVocabulary CATALOG = FoodCatalogLoader.loadFromClasspath();

    final AreaPresets areas = mock(AreaPresets.class);

    SnapshotIngestionService service() {
        SnapshotPlanner planner = new SnapshotPlanner(areas, placeCells, coverage, ingestion,
            new SnapshotFoodQueries(CATALOG));
        RestaurantSyncService sync = new RestaurantSyncService(goong,
            new GoongPlaceMapper(new ObjectMapper(), new GoongProperties()), restaurants, new AppProperties());
        SnapshotRunLock lock = () -> {
            if (!lockFree) return Optional.empty();
            locksTaken++;
            return Optional.of(() -> { });
        };
        return new SnapshotIngestionService(planner, goong, budget, sync, restaurants, coverage, ingestion, lock,
            new ObjectMapper(), new FoodClassifier(new FoodMatcher(CATALOG), mock(RestaurantFoodRepository.class)));
    }

    SnapshotProperties props(Consumer<SnapshotProperties> tweak) {
        SnapshotProperties p = new SnapshotProperties();
        p.setCells(new ArrayList<>(List.of(CELL_A)));
        p.setBudget(20);
        p.setExecute(true);
        p.setReportDir(reports.toString());
        tweak.accept(p);
        return p;
    }

    SnapshotRunReport run(Consumer<SnapshotProperties> tweak) {
        return service().run(props(tweak), List.of());
    }

    // ── Dry run, guards, lock ───────────────────────────────────────────────

    @Test
    void aDryRunPlansAndReportsWithZeroGoongRequests() throws Exception {
        SnapshotRunReport report = run(p -> {
            p.setExecute(false);
            p.setCells(new ArrayList<>(List.of(CELL_A, CELL_B)));
        });

        assertThat(report.getStopReason()).isEqualTo(StopReason.DRY_RUN);
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
        assertThat(budget.snapshot().used()).isZero();
        assertThat(report.getPlannedCells()).extracting(SnapshotRunReport.PlannedCell::getCellKey)
            .containsExactly(CELL_A, CELL_B);
        assertThat(report.getDiscoveryCap()).isEqualTo(4);
        assertThat(report.getDetailReserve()).isEqualTo(16);
        verify(restaurants, never()).save(any());
        verify(placeCells, never()).save(any());
        try (Stream<Path> files = Files.list(reports)) {
            assertThat(files).hasSize(1);   // the report is written
        }
    }

    @Test
    void theExecuteFlagIsRequiredToSpendAnything() {
        SnapshotRunReport report = run(p -> p.setExecute(false));
        assertThat(report.getStopReason()).isEqualTo(StopReason.DRY_RUN);
        assertThat(goong.calls).isEmpty();
    }

    @Test
    void guardsRefuseBeforeAnyGoongRequestOrLock() {
        assertRefused(p -> p.setBudget(null), "budget is required");
        assertRefused(p -> p.setBudget(0), "must be 1..300");
        assertRefused(p -> p.setBudget(301), "must be 1..300");
        assertRefused(p -> p.setBudget(101), "needs app.snapshot.large=true");
        assertRefused(p -> p.setMaxCells(51), "max-cells must be 1..50");
        assertRefused(p -> p.setMaxCells(0), "max-cells must be 1..50");
        assertRefused(p -> p.setCells(new ArrayList<>()), "give app.snapshot.areas");
        assertRefused(p -> p.setResume("old.json"), "cannot be combined");
        ingestion.setSeedOnStartup(true);
        assertRefused(p -> { }, "triggers must be off");
        ingestion.setSeedOnStartup(false);
        assertThat(locksTaken).isZero();
    }

    @Test
    void aLargeBudgetIsAcceptedOnlyWithLargeTrue() {
        SnapshotRunReport report = run(p -> {
            p.setBudget(150);
            p.setLarge(true);
            p.setExecute(false);
        });
        assertThat(report.getStopReason()).isEqualTo(StopReason.DRY_RUN);
    }

    @Test
    void anEnvironmentProblemRefusesTheRun() {
        SnapshotRunReport report = service().run(props(p -> { }), List.of("the web server must not run"));
        assertThat(report.getStopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(goong.calls).isEmpty();
    }

    @Test
    void anotherRunHoldingTheLockMeansNothingIsDone() {
        lockFree = false;
        SnapshotRunReport report = run(p -> { });
        assertThat(report.getStopReason()).isEqualTo(StopReason.LOCKED);
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
    }

    // ── Dedupe, known places ────────────────────────────────────────────────

    @Test
    void placeIdsAreDeduplicatedAcrossTheWholeRunBeforePlaceDetail() {
        goong.predict("restaurant", centerA, "p1", "p2");
        goong.predict("cafe", centerA, "p2");
        goong.predict("restaurant", centerB, "p2", "p3");
        goong.predict("cafe", centerB, "p3");
        goong.place("p1", centerA, 0.0005);
        goong.place("p2", centerA, 0.0007);
        goong.place("p3", centerB, 0.0005);

        SnapshotRunReport report = run(p -> p.setCells(new ArrayList<>(List.of(CELL_A, CELL_B))));

        assertThat(goong.detailCalls()).containsExactly("p1", "p2", "p3");   // each once
        assertThat(report.getPredictions()).isEqualTo(6);
        assertThat(report.getDistinctIds()).isEqualTo(3);
        assertThat(report.getDuplicates()).isEqualTo(3);
        assertThat(report.getNewRestaurants()).hasSize(3);
        assertThat(report.getRequests().getAutocomplete()).isEqualTo(4);
        assertThat(report.getRequests().getPlaceDetail()).isEqualTo(3);
        assertThat(report.getStopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void knownPlaceIdsAreSkippedWhateverTheirAge() {
        known.add(row(1L, "p1", "Phở Hòa", "260C Pasteur, Quận 3", centerA[0], centerA[1]));
        goong.predict("restaurant", centerA, "p1", "p2");
        goong.place("p2", centerA, 0.0005);

        SnapshotRunReport report = run(p -> { });

        assertThat(goong.detailCalls()).containsExactly("p2");
        assertThat(report.getKnownById()).isEqualTo(1);
        assertThat(saved).extracting(Restaurant::getGoongPlaceId).containsExactly("p2");
    }

    @Test
    void anExactNameAndAddressMatchIsKnownWithoutPlaceDetail() {
        known.add(row(1L, "old-id-1", "Phở Hòa", "260C Pasteur, Phường 8, Quận 3", centerA[0], centerA[1]));
        known.add(row(2L, "old-id-2", "Bún Đậu Ngõ Nhỏ", "Bún Đậu Ngõ Nhỏ, 156C Pasteur, Bến Nghé", centerA[0], centerA[1]));
        goong.predictions.put(goong.key("restaurant", centerA), List.of(
            new GoongPrediction("new-1", null, new GoongPrediction.StructuredFormatting("phở hòa", "260C  Pasteur, Phường 8, Quận 3")),
            new GoongPrediction("new-2", null, new GoongPrediction.StructuredFormatting("Bún Đậu Ngõ Nhỏ", "156C Pasteur, Bến Nghé")),
            new GoongPrediction("new-3", null, new GoongPrediction.StructuredFormatting("Phở Hòa", "99 Another Street"))));
        goong.place("new-3", centerA, 0.0005);

        SnapshotRunReport report = run(p -> { });

        assertThat(report.getKnownByText()).isEqualTo(2);
        assertThat(goong.detailCalls()).containsExactly("new-3");   // a different address is not "known"
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    void invalidCoordinatesAreRejectedNotSavedAndRecorded() {
        goong.predict("restaurant", centerA, "bad");
        goong.details.put("bad", place("bad", "Quán Lạ", 200.0, 106.70));

        SnapshotRunReport report = run(p -> { });

        assertThat(saved).isEmpty();
        assertThat(report.getRejected()).singleElement().satisfies(r -> {
            assertThat(r.getPlaceId()).isEqualTo("bad");
            assertThat(r.getReason()).isEqualTo("INVALID_COORDINATES");
        });
    }

    @Test
    void aPlaceOutsideTheTargetRegionIsRejectedAndNeverFetchedAgain() {
        goong.predict("restaurant", centerA, "far");
        goong.details.put("far", place("far", "Quán Xa", centerA[0] + 0.045, centerA[1]));   // ~5 km, still HCMC

        SnapshotRunReport first = run(p -> { });
        assertThat(saved).isEmpty();
        assertThat(first.getRejected()).singleElement().satisfies(r -> assertThat(r.getReason()).isEqualTo("OUTSIDE_REGION"));

        goong.calls.clear();
        SnapshotRunReport second = run(p -> { });   // the same prediction again
        assertThat(goong.detailCalls()).isEmpty();   // remembered from the first report
        assertThat(second.getPreviouslyRejected()).isEqualTo(1);
    }

    // ── Budget, partial cells, resume ───────────────────────────────────────

    @Test
    void whenThePlaceDetailBudgetRunsOutThePendingIdsAreReportedAndTheCellIsNotRecorded() {
        goong.predict("restaurant", centerA, "p1", "p2", "p3");
        goong.predict("cafe", centerA, "p4", "p5");
        for (String id : List.of("p1", "p2", "p3", "p4", "p5")) goong.place(id, centerA, 0.0003);

        // budget 5: discovery share floor(2.0) = 2 = one cell's two Autocomplete calls; 3 left for Place Detail
        SnapshotRunReport report = run(p -> p.setBudget(5));

        assertThat(report.getStopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(budget.snapshot().used()).isEqualTo(5);
        assertThat(goong.detailCalls()).containsExactly("p1", "p2", "p3");
        assertThat(report.getPendingIds()).containsExactly("p4", "p5");
        assertThat(report.getPartialCells()).singleElement().satisfies(c -> {
            assertThat(c.getCellKey()).isEqualTo(CELL_A);
            assertThat(c.getKeywordsPending()).isEmpty();
            assertThat(c.getPendingPlaces()).extracting(SnapshotPlan.PendingPlace::placeId).containsExactly("p4", "p5");
        });
        assertThat(report.getCompletedCells()).isEmpty();
        verify(placeCells, never()).save(any());
        assertThat(budget.isArmed()).isFalse();   // disarmed after the run
    }

    @Test
    void discoveryNeverEatsThePlaceDetailReserve() {
        goong.predict("restaurant", centerA, "p1");
        goong.predict("cafe", centerA, "p2");
        goong.place("p1", centerA, 0.0003);
        goong.place("p2", centerA, 0.0003);

        // budget 7 → discovery share 2 → 1 cell (2 Autocomplete), reserve 5, even with 2 cells asked
        SnapshotRunReport report = run(p -> {
            p.setBudget(7);
            p.setCells(new ArrayList<>(List.of(CELL_A, CELL_B)));
            p.setMaxCells(2);
        });

        assertThat(report.getPlannedCells()).hasSize(1);
        assertThat(report.getDiscoveryCap()).isEqualTo(2);
        assertThat(report.getDetailReserve()).isEqualTo(5);
        assertThat(report.getRequests().getAutocomplete()).isEqualTo(2);
    }

    @Test
    void aCellIsRecordedOnlyOnceItsPlannedWorkIsComplete() {
        goong.predict("restaurant", centerA, "a1");
        goong.predict("restaurant", centerB, "b1", "b2", "b3");
        goong.place("a1", centerA, 0.0003);
        for (String id : List.of("b1", "b2", "b3")) goong.place(id, centerB, 0.0003);

        // budget 10: 4 Autocomplete (2 cells), 6 Place Detail; cell A completes; B too → both recorded
        SnapshotRunReport complete = run(p -> {
            p.setBudget(10);
            p.setCells(new ArrayList<>(List.of(CELL_A, CELL_B)));
            p.setMaxCells(2);
        });
        assertThat(complete.getCompletedCells()).extracting(SnapshotRunReport.CompletedCell::getCellKey)
            .containsExactly(CELL_A, CELL_B);

        ArgumentCaptor<PlaceCell> recorded = ArgumentCaptor.forClass(PlaceCell.class);
        verify(placeCells, times(2)).save(recorded.capture());
        assertThat(recorded.getAllValues()).allSatisfy(c -> assertThat(c.getSource()).isEqualTo(PlaceCell.Source.MANUAL));
        assertThat(recorded.getAllValues().get(0).getPlaceCount()).isEqualTo(1);   // a1 is local to A
    }

    @Test
    void onlyTheCompletedCellIsRecordedWhenAnotherStaysPartial() {
        // budget 10: 4 Autocomplete (2 cells) + 6 Place Detail, but 7 new ids (a1, b1..b6):
        // a1 completes cell A; b6 is left pending, so cell B stays partial
        goong.predict("restaurant", centerA, "a1");
        goong.predict("restaurant", centerB, "b1", "b2", "b3", "b4", "b5");
        goong.predict("cafe", centerB, "b6");
        goong.place("a1", centerA, 0.0003);
        for (String id : List.of("b1", "b2", "b3", "b4", "b5", "b6")) goong.place(id, centerB, 0.0003);

        SnapshotRunReport report = run(p -> {
            p.setBudget(10);
            p.setCells(new ArrayList<>(List.of(CELL_A, CELL_B)));
            p.setMaxCells(2);
        });

        assertThat(report.getStopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(report.getCompletedCells()).extracting(SnapshotRunReport.CompletedCell::getCellKey).containsExactly(CELL_A);
        assertThat(report.getPartialCells()).extracting(SnapshotRunReport.PartialCell::getCellKey).containsExactly(CELL_B);
        ArgumentCaptor<PlaceCell> recorded = ArgumentCaptor.forClass(PlaceCell.class);
        verify(placeCells, times(1)).save(recorded.capture());
        assertThat(recorded.getValue().getCellKey()).isEqualTo(CELL_A);
    }

    @Test
    void resumingFinishesThePendingPlacesWithoutRepeatingAutocomplete() throws Exception {
        goong.predict("restaurant", centerA, "p1", "p2", "p3");
        goong.predict("cafe", centerA, "p4", "p5");
        for (String id : List.of("p1", "p2", "p3", "p4", "p5")) goong.place(id, centerA, 0.0003);
        run(p -> p.setBudget(5));
        Path firstReport;
        try (Stream<Path> files = Files.list(reports)) {
            firstReport = files.findFirst().orElseThrow();
        }
        // p1..p3 are now stored
        saved.forEach(r -> known.add(row(r.getId(), r.getGoongPlaceId(), r.getName(), r.getAddress(), r.getLatitude(), r.getLongitude())));
        goong.calls.clear();

        SnapshotRunReport resumed = service().run(props(p -> {
            p.setCells(new ArrayList<>());
            p.setResume(firstReport.toString());
            p.setBudget(10);
        }), List.of());

        assertThat(resumed.getMode()).isEqualTo(SnapshotRunReport.Mode.RESUME);
        assertThat(goong.autocompleteCalls()).isEmpty();
        assertThat(goong.detailCalls()).containsExactly("p4", "p5");
        assertThat(resumed.getStopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(resumed.getCompletedCells()).extracting(SnapshotRunReport.CompletedCell::getCellKey).containsExactly(CELL_A);
    }

    @Test
    void a429StopsTheRunAndLeavesTheRestPending() {
        goong.predict("restaurant", centerA, "p1", "p2");
        goong.place("p1", centerA, 0.0003);
        goong.details.put("p2", new GoongRunAbortedException(GoongRunAbortedException.Reason.RATE_LIMITED, "429", null));

        SnapshotRunReport report = run(p -> { });

        assertThat(report.getStopReason()).isEqualTo(StopReason.RATE_LIMITED);
        assertThat(report.getPendingIds()).containsExactly("p2");
        verify(placeCells, never()).save(any());
    }

    // ── Food probes ─────────────────────────────────────────────────────────

    static final AreaPreset BINH_THANH = new AreaPreset("binh-thanh", "Bình Thạnh", 60, 10.8024, 106.7136, 2500);
    static final double[] BT = {10.8024, 106.7136};

    SnapshotRunReport runFood(Consumer<SnapshotProperties> tweak) {
        when(areas.get("binh-thanh")).thenReturn(Optional.of(BINH_THANH));
        return run(p -> {
            p.setCells(new ArrayList<>());
            p.setAreas(new ArrayList<>(List.of("binh-thanh")));
            p.setFoods(new ArrayList<>(List.of("pho", "com", "lau")));
            tweak.accept(p);
        });
    }

    @Test
    void aFoodProbeDryRunPlansOneQueryPerFoodAtTheAreaCentreWithZeroGoongRequests() {
        SnapshotRunReport report = runFood(p -> p.setExecute(false));

        assertThat(report.getStopReason()).isEqualTo(StopReason.DRY_RUN);
        assertThat(report.getDiscovery()).isEqualTo(SnapshotRunReport.Discovery.FOOD_PROBES);
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
        assertThat(report.getPlannedCells()).extracting(SnapshotRunReport.PlannedCell::getCellKey)
            .containsExactly("food:pho@binh-thanh", "food:com@binh-thanh", "food:lau@binh-thanh");
        assertThat(report.getPlannedCells()).extracting(SnapshotRunReport.PlannedCell::getKeywords)
            .containsExactly(List.of("Phở"), List.of("Cơm"), List.of("Lẩu"));    // the catalog's labels
        assertThat(report.getPlannedCells()).allSatisfy(c -> {
            assertThat(c.getLat()).isEqualTo(BT[0]);
            assertThat(c.getLng()).isEqualTo(BT[1]);
            assertThat(c.getRadiusMeters()).isEqualTo(2500);
            assertThat(c.getAreaKey()).isEqualTo("binh-thanh");
        });
        assertThat(report.getDiscoveryCap()).isEqualTo(3);
        assertThat(report.getDetailReserve()).isEqualTo(17);
        assertThat(report.getRegion()).singleElement()
            .satisfies(c -> assertThat(c.radiusMeters()).isEqualTo(3000));
    }

    @Test
    void foodProbesShareTheBudgetDedupeKnownSkippingAndReportPerQuery() {
        known.add(row(7L, "k1", "Quán Cũ", "1 Đường Cũ", BT[0], BT[1]));
        goong.predict("Phở", BT, "f1", "f2", "k1");
        goong.predict("Cơm", BT, "f2", "f3");
        goong.predict("Lẩu", BT);
        goong.details.put("f1", place("f1", "Phở Bò Hà Nội", BT[0] + 0.003, BT[1]));
        goong.details.put("f2", place("f2", "Cơm Tấm Sài Gòn", BT[0] + 0.006, BT[1]));
        goong.details.put("f3", place("f3", "Cơm Gà Xối Mỡ", BT[0] - 0.004, BT[1]));

        SnapshotRunReport report = runFood(p -> { });

        // exactly the planned queries, in order, at the area centre and radius; unused budget is not spent
        assertThat(goong.autocompleteCalls()).containsExactly("AC Phở", "AC Cơm", "AC Lẩu");
        assertThat(goong.radii).containsOnly(2500);
        assertThat(goong.detailCalls()).containsExactly("f1", "f2", "f3");   // f2 once, k1 never
        assertThat(report.getRequests().getUsed()).isEqualTo(6);
        assertThat(report.getRequests().getAutocomplete()).isEqualTo(3);
        assertThat(report.getRequests().getPlaceDetail()).isEqualTo(3);
        assertThat(report.getStopReason()).isEqualTo(StopReason.COMPLETED);

        // run totals
        assertThat(report.getPredictions()).isEqualTo(5);
        assertThat(report.getDistinctIds()).isEqualTo(4);
        assertThat(report.getDuplicates()).isEqualTo(1);
        assertThat(report.getKnownById()).isEqualTo(1);
        assertThat(report.getUnknownIds()).isEqualTo(3);
        assertThat(report.getNewRestaurants()).hasSize(3);
        assertThat(report.getYield().getNewPerRequest()).isEqualTo(0.5);

        // per query: place_ids are charged to the query that found them first
        List<SnapshotRunReport.QueryResult> q = report.getQueries();
        assertThat(q).extracting(SnapshotRunReport.QueryResult::getQuery).containsExactly("Phở", "Cơm", "Lẩu");
        assertThat(q.get(0)).satisfies(pho -> {
            assertThat(pho.getFoodKey()).isEqualTo("pho");
            assertThat(pho.getArea()).isEqualTo("binh-thanh");
            assertThat(pho.isRan()).isTrue();
            assertThat(pho.getPredictions()).isEqualTo(3);
            assertThat(pho.getDistinctIds()).isEqualTo(3);
            assertThat(pho.getNewToRun()).isEqualTo(3);
            assertThat(pho.getKnownById()).isEqualTo(1);
            assertThat(pho.getUnknownIds()).isEqualTo(2);
            assertThat(pho.getAutocompleteRequests()).isEqualTo(1);
            assertThat(pho.getPlaceDetailRequests()).isEqualTo(2);
            assertThat(pho.getRequests()).isEqualTo(3);
            assertThat(pho.getNewRestaurants()).isEqualTo(2);
            assertThat(pho.getNewPerRequest()).isEqualTo(0.667);
        });
        assertThat(q.get(1)).satisfies(com -> {
            assertThat(com.getDistinctIds()).isEqualTo(2);
            assertThat(com.getAlreadySeenInRun()).isEqualTo(1);   // f2, charged to "Phở"
            assertThat(com.getNewToRun()).isEqualTo(1);
            assertThat(com.getPlaceDetailRequests()).isEqualTo(1);
            assertThat(com.getNewRestaurants()).isEqualTo(1);
            assertThat(com.getNewPerRequest()).isEqualTo(0.5);
        });
        assertThat(q.get(2)).satisfies(lau -> {
            assertThat(lau.isRan()).isTrue();
            assertThat(lau.getPredictions()).isZero();
            assertThat(lau.getRequests()).isEqualTo(1);
            assertThat(lau.getNewPerRequest()).isEqualTo(0.0);
        });

        // do the new places actually serve the probed food? (the server's own classification)
        assertThat(report.getNewRestaurants()).satisfiesExactly(
            f1 -> {
                assertThat(f1.getQuery()).isEqualTo("Phở");
                assertThat(f1.getFoodKeys()).contains("pho");
                assertThat(f1.getMatchesProbeFood()).isTrue();
                assertThat(f1.getDistanceMeters()).isBetween(320, 345);
            },
            f2 -> {
                assertThat(f2.getQuery()).isEqualTo("Phở");
                assertThat(f2.getFoodKeys()).contains("com");
                assertThat(f2.getMatchesProbeFood()).isFalse();
            },
            f3 -> {
                assertThat(f3.getQuery()).isEqualTo("Cơm");
                assertThat(f3.getMatchesProbeFood()).isTrue();
            });

        // food probes are not map cells
        assertThat(report.getCompletedProbes())
            .containsExactly("food:pho@binh-thanh", "food:com@binh-thanh", "food:lau@binh-thanh");
        assertThat(report.getCompletedCells()).isEmpty();
        verify(placeCells, never()).save(any());
    }

    @Test
    void aRetriedQueryCountsTwiceAndDiscoveryNeverExceedsThePlannedCalls() {
        goong.retryOnce.add("Phở");
        goong.predict("Phở", BT, "f1");
        goong.predict("Cơm", BT, "f2");
        goong.place("f1", BT, 0.001);
        goong.place("f2", BT, 0.001);

        SnapshotRunReport report = runFood(p -> { });

        assertThat(report.getStopReason()).isEqualTo(StopReason.DISCOVERY_CAP_REACHED);
        assertThat(report.getRequests().getAutocomplete()).isEqualTo(3);   // the planned 3, retry included
        assertThat(report.getRequests().getRetries()).isEqualTo(1);
        assertThat(goong.autocompleteCalls()).containsExactly("AC Phở", "AC Cơm");
        assertThat(report.getQueries().get(0).getAutocompleteRequests()).isEqualTo(2);
        assertThat(report.getQueries().get(2)).satisfies(lau -> {
            assertThat(lau.isRan()).isFalse();
            assertThat(lau.getAutocompleteRequests()).isZero();
        });
        assertThat(report.getPartialCells()).singleElement().satisfies(pc -> {
            assertThat(pc.getCellKey()).isEqualTo("food:lau@binh-thanh");
            assertThat(pc.getKeywordsPending()).containsExactly("Lẩu");
            assertThat(pc.getFoodKey()).isEqualTo("lau");
            assertThat(pc.getRadiusMeters()).isEqualTo(2500);
        });
        assertThat(goong.detailCalls()).containsExactly("f1", "f2");   // the Place Detail reserve is intact
    }

    @Test
    void foodProbeGuardsRefuseBeforeAnyGoongRequest() {
        assertRefusedFood(p -> p.setAreas(new ArrayList<>(List.of("binh-thanh", "quan-1"))), "exactly one");
        assertRefusedFood(p -> p.setAreas(new ArrayList<>()), "exactly one");
        assertRefusedFood(p -> p.setCells(new ArrayList<>(List.of(CELL_A))), "cannot be combined with app.snapshot.cells");
        assertRefusedFood(p -> p.setFoods(new ArrayList<>(List.of("pho", "khong-co"))), "not a discoverable catalog food");
        assertRefusedFood(p -> p.setFoods(new ArrayList<>(List.of("tra"))), "not a discoverable catalog food");
        assertRefusedFood(p -> p.setBudget(5), "exceed the discovery share");   // 3 queries > floor(2)
        assertThat(goong.calls).isEmpty();
    }

    @Test
    void aPartialFoodProbeResumesWithoutRepeatingItsAutocomplete() throws Exception {
        goong.predict("Phở", BT, "f1", "f2", "f3");
        for (String id : List.of("f1", "f2", "f3")) goong.place(id, BT, 0.001);

        // budget 3: discovery share floor(1.2) = 1 query; 2 Place Detail, so f3 stays pending
        SnapshotRunReport first = runFood(p -> {
            p.setFoods(new ArrayList<>(List.of("pho")));
            p.setBudget(3);
        });
        assertThat(first.getStopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(first.getPendingIds()).containsExactly("f3");
        assertThat(first.getQueries().get(0).getPending()).isEqualTo(1);
        Path report;
        try (Stream<Path> files = Files.list(reports)) {
            report = files.findFirst().orElseThrow();
        }
        saved.forEach(r -> known.add(row(r.getId(), r.getGoongPlaceId(), r.getName(), r.getAddress(), r.getLatitude(), r.getLongitude())));
        goong.calls.clear();

        SnapshotRunReport resumed = service().run(props(p -> {
            p.setCells(new ArrayList<>());
            p.setResume(report.toString());
            p.setBudget(5);
        }), List.of());

        assertThat(goong.autocompleteCalls()).isEmpty();
        assertThat(goong.detailCalls()).containsExactly("f3");
        assertThat(resumed.getCompletedProbes()).containsExactly("food:pho@binh-thanh");
        assertThat(resumed.getQueries()).singleElement()
            .satisfies(q -> assertThat(q.getQuery()).isEqualTo(SnapshotIngestionService.RESUMED_QUERY));
        verify(placeCells, never()).save(any());
    }

    void assertRefusedFood(Consumer<SnapshotProperties> tweak, String why) {
        SnapshotRunReport report = runFood(tweak);
        assertThat(report.getStopReason()).as(why).isEqualTo(StopReason.REFUSED);
        assertThat(report.getStopDetail()).contains(why);
        assertThat(budget.isArmed()).isFalse();
    }

    // ── Ledger: rolling 24 h ceiling, food × area pairs ─────────────────────

    /** An earlier executed run, ended an hour ago, that used {@code used} Goong requests. */
    void priorRun(String file, int used) throws Exception {
        SnapshotRunReport r = new SnapshotRunReport();
        r.setRunId(file);
        r.setMode(SnapshotRunReport.Mode.EXECUTE);
        r.setStopReason(StopReason.COMPLETED);
        r.setBudget(used);
        r.setStartedAt(Instant.now().minus(Duration.ofMinutes(70)).toString());
        r.setFinishedAt(Instant.now().minus(Duration.ofMinutes(60)).toString());
        r.getRequests().setUsed(used);
        new ObjectMapper().writeValue(reports.resolve(file).toFile(), r);
    }

    @Test
    void theDailyCeilingDefaultsTo120AndMustBeSane() {
        assertThat(new SnapshotProperties().getDailyCeiling()).isEqualTo(120);
        assertRefused(p -> p.setDailyCeiling(0), "daily-ceiling must be 1..1000");
        assertRefused(p -> p.setDailyCeiling(1001), "daily-ceiling must be 1..1000");
    }

    @Test
    void withNoPriorUsageTheRunIsAllowed() {
        SnapshotRunReport report = run(p -> { });
        assertThat(report.getStopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(report.getLedger()).satisfies(l -> {
            assertThat(l.getPrevious24hUsage()).isZero();
            assertThat(l.getRequestedBudget()).isEqualTo(20);
            assertThat(l.getProjected24hUsage()).isEqualTo(20);
            assertThat(l.getDailyCeiling()).isEqualTo(120);
            assertThat(l.isAllowed()).isTrue();
        });
    }

    @Test
    void usageExactlyAtTheCeilingIsAllowed() throws Exception {
        priorRun("prior.json", 100);
        SnapshotRunReport report = run(p -> { });   // 100 + 20 = 120
        assertThat(report.getStopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(report.getLedger().getProjected24hUsage()).isEqualTo(120);
        assertThat(goong.autocompleteCalls()).hasSize(2);
    }

    @Test
    void usageAboveTheCeilingIsRefusedBeforeAnyGoongRequest() throws Exception {
        priorRun("prior.json", 100);
        SnapshotRunReport report = run(p -> p.setBudget(21));   // 121

        assertThat(report.getStopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(report.getStopDetail()).contains("(100) + budget 21 = 121 exceeds the daily ceiling 120");
        assertThat(report.getLedger().isAllowed()).isFalse();
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
        assertThat(budget.snapshot().used()).isZero();
        verify(restaurants, never()).save(any());
    }

    @Test
    void aConfiguredCeilingIsHonoured() throws Exception {
        priorRun("prior.json", 30);
        SnapshotRunReport report = run(p -> p.setDailyCeiling(40));
        assertThat(report.getStopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(report.getStopDetail()).contains("= 50 exceeds the daily ceiling 40");
        assertThat(goong.calls).isEmpty();
    }

    @Test
    void aDryRunShowsTheLedgerAndWhetherExecutionWouldBeAllowed() throws Exception {
        priorRun("prior.json", 43);
        SnapshotRunReport allowed = run(p -> {
            p.setExecute(false);
            p.setBudget(40);
        });
        assertThat(allowed.getStopReason()).isEqualTo(StopReason.DRY_RUN);
        assertThat(allowed.getStopDetail()).contains("execution would be allowed");
        assertThat(allowed.getLedger()).satisfies(l -> {
            assertThat(l.getReportsRead()).isEqualTo(1);
            assertThat(l.getPrevious24hUsage()).isEqualTo(43);
            assertThat(l.getRequestedBudget()).isEqualTo(40);
            assertThat(l.getProjected24hUsage()).isEqualTo(83);
            assertThat(l.isAllowed()).isTrue();
        });

        priorRun("prior-2.json", 40);   // 43 + 40 + 40 = 123
        SnapshotRunReport refused = run(p -> {
            p.setExecute(false);
            p.setBudget(40);
        });
        assertThat(refused.getStopReason()).isEqualTo(StopReason.DRY_RUN);   // the plan is still shown
        assertThat(refused.getStopDetail()).contains("execution would be refused", "= 123 exceeds the daily ceiling 120");
        assertThat(refused.getLedger().isAllowed()).isFalse();
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
    }

    @Test
    void anUnreadableReportFailsClosed() throws Exception {
        Files.writeString(reports.resolve("damaged.json"), "{\"requests\":{\"used\":");

        SnapshotRunReport dry = run(p -> p.setExecute(false));
        assertThat(dry.getLedger().isAllowed()).isFalse();
        assertThat(dry.getLedger().getUnreadableReports()).singleElement()
            .satisfies(u -> assertThat(u).startsWith("damaged.json"));

        SnapshotRunReport report = run(p -> { });
        assertThat(report.getStopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(report.getStopDetail()).contains("cannot read snapshot report(s) damaged.json", "fails closed");
        assertThat(goong.calls).isEmpty();
    }

    @Test
    void theRunIsOnTheLedgerBeforeItsFirstGoongRequest() throws Exception {
        List<String> seen = new ArrayList<>();
        goong.beforeFirstRequest = () -> {
            try (Stream<Path> files = Files.list(reports)) {
                List<Path> all = files.toList();
                seen.add(all.size() + " " + new ObjectMapper().readTree(all.get(0).toFile()).path("stopReason").asText());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            // a run that died now would still count its whole budget
            seen.add("usage " + new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now()).usageInWindow());
        };

        SnapshotRunReport report = run(p -> { });

        assertThat(seen).containsExactly("1 IN_PROGRESS", "usage 20");
        assertThat(report.getStopReason()).isEqualTo(StopReason.COMPLETED);
        try (Stream<Path> files = Files.list(reports)) {
            List<Path> all = files.toList();   // the marker became the final report: still one file
            assertThat(all).hasSize(1);
            assertThat(new ObjectMapper().readTree(all.get(0).toFile()).path("stopReason").asText()).isEqualTo("COMPLETED");
        }
        assertThat(new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now()).usageInWindow())
            .isEqualTo(report.getRequests().getUsed());   // the actual usage, not the budget
    }

    @Test
    void aCompletedFoodAreaPairIsNeverProbedAgainNorSilentlyDropped() {
        goong.predict("Phở", BT, "f1");
        goong.place("f1", BT, 0.001);
        assertThat(runFood(p -> p.setFoods(new ArrayList<>(List.of("pho")))).getCompletedProbes())
            .containsExactly("food:pho@binh-thanh");
        goong.calls.clear();

        SnapshotRunReport again = runFood(p -> { });   // pho, com, lau

        assertThat(again.getStopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(again.getStopDetail()).contains("food:pho@binh-thanh was already probed by an earlier run");
        assertThat(again.getLedger().getCompletedPairs()).containsExactly("food:pho@binh-thanh");
        assertThat(again.getPlannedCells()).extracting(SnapshotRunReport.PlannedCell::getCellKey)   // not trimmed
            .containsExactly("food:pho@binh-thanh", "food:com@binh-thanh", "food:lau@binh-thanh");
        assertThat(goong.calls).isEmpty();

        // the remaining foods can be planned explicitly
        assertThat(runFood(p -> p.setFoods(new ArrayList<>(List.of("com", "lau")))).getStopReason())
            .isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void aPendingFoodAreaPairMustBeResumedNotProbedAgain() {
        goong.predict("Phở", BT, "f1", "f2", "f3");
        for (String id : List.of("f1", "f2", "f3")) goong.place(id, BT, 0.001);
        runFood(p -> {
            p.setFoods(new ArrayList<>(List.of("pho")));
            p.setBudget(3);
        });
        goong.calls.clear();

        SnapshotRunReport again = runFood(p -> p.setFoods(new ArrayList<>(List.of("pho", "com"))));

        assertThat(again.getStopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(again.getStopDetail()).contains("food:pho@binh-thanh is pending in snapshot-", "app.snapshot.resume");
        assertThat(again.getLedger().getPendingPairs()).containsExactly("food:pho@binh-thanh");
        assertThat(goong.calls).isEmpty();
        // resuming it is allowed — aPartialFoodProbeResumesWithoutRepeatingItsAutocomplete
    }

    // ── Food relevance gate (food probes only) ──────────────────────────────

    SnapshotRunReport.NewRestaurant newRestaurant(SnapshotRunReport report, String placeId) {
        return report.getNewRestaurants().stream().filter(n -> n.getPlaceId().equals(placeId)).findFirst().orElseThrow();
    }

    @Test
    void aFoodProbeSavesCatalogFoodsOnlyAndCountsOnlyTheProbedFoodAsRelevant() {
        goong.predict("Phở", BT, "match", "other", "class-only", "nofood");
        goong.details.put("match", place("match", "Phở Bò Hà Nội", BT[0] + 0.003, BT[1]));
        goong.details.put("other", place("other", "Cơm Tấm Sài Gòn", BT[0] + 0.004, BT[1]));
        goong.details.put("class-only", place("class-only", "Xôi Gà Bà Ba", BT[0] + 0.005, BT[1]));
        goong.details.put("nofood", place("nofood", "Cửa hàng Minh Anh", BT[0] + 0.006, BT[1]));

        SnapshotRunReport report = runFood(p -> p.setFoods(new ArrayList<>(List.of("pho"))));

        assertThat(saved).extracting(Restaurant::getGoongPlaceId).containsExactly("match", "other", "class-only");
        assertThat(newRestaurant(report, "match")).satisfies(n -> {
            assertThat(n.getRelevance()).isEqualTo("MATCH");
            assertThat(n.getFoodKeys()).contains("pho");
            assertThat(n.getClassifierDisagrees()).isFalse();
        });
        assertThat(newRestaurant(report, "other")).satisfies(n -> {
            assertThat(n.getRelevance()).isEqualTo("OTHER_FOOD");
            assertThat(n.getFoodKeys()).contains("com").doesNotContain("pho");
        });
        assertThat(newRestaurant(report, "class-only")).satisfies(n -> {   // xôi: classification-only
            assertThat(n.getRelevance()).isEqualTo("OTHER_FOOD");
            assertThat(n.getFoodKeys()).contains("xoi");
        });
        assertThat(report.getRejected()).singleElement().satisfies(r -> {
            assertThat(r.getPlaceId()).isEqualTo("nofood");
            assertThat(r.getReason()).isEqualTo("NO_FOOD_MATCH");
            assertThat(r.getName()).isEqualTo("Cửa hàng Minh Anh");
            assertThat(r.getClassifierDisagrees()).isFalse();
        });
        assertThat(report.getQueries()).singleElement().satisfies(q -> {
            assertThat(q.getNewRestaurants()).isEqualTo(3);
            assertThat(q.getFoodRelevantNew()).isEqualTo(1);
            assertThat(q.getOtherFoodNew()).isEqualTo(2);
            assertThat(q.getRejected()).isEqualTo(1);
            assertThat(q.getRejectedNoFoodMatch()).isEqualTo(1);
            assertThat(q.getRejectedByReason()).containsExactly(Map.entry("NO_FOOD_MATCH", 1));
            assertThat(q.getRequests()).isEqualTo(5);
            assertThat(q.getNewPerRequest()).isEqualTo(0.6);
            assertThat(q.getRelevantPerRequest()).isEqualTo(0.2);
        });
        // a NO_FOOD_MATCH id is remembered like any rejection: never fetched again
        goong.calls.clear();
        goong.predict("Cơm", BT, "nofood");
        SnapshotRunReport com = runFood(p -> p.setFoods(new ArrayList<>(List.of("com"))));
        assertThat(goong.detailCalls()).isEmpty();
        assertThat(com.getPreviouslyRejected()).isEqualTo(1);
    }

    @Test
    void aPlaceServingTheProbedFoodAndOthersIsRelevant() {
        goong.predict("Chè", BT, "c1");
        goong.details.put("c1", place("c1", "Chè Xôi Ngọc Thái", BT[0] + 0.002, BT[1]));

        SnapshotRunReport report = runFood(p -> p.setFoods(new ArrayList<>(List.of("che"))));

        assertThat(newRestaurant(report, "c1")).satisfies(n -> {
            assertThat(n.getFoodKeys()).contains("che", "xoi");
            assertThat(n.getRelevance()).isEqualTo("MATCH");
        });
        assertThat(report.getQueries().get(0).getFoodRelevantNew()).isEqualTo(1);
        assertThat(report.getQueries().get(0).getOtherFoodNew()).isZero();
    }

    @Test
    void whenTheClassifierDisagreesWithTheQueryTheClassifierWins() {
        goong.predict("Mì", BT, "b1");
        goong.details.put("b1", place("b1", "Bánh mì Hà Nội", BT[0] + 0.002, BT[1]));

        SnapshotRunReport report = runFood(p -> p.setFoods(new ArrayList<>(List.of("mi"))));

        assertThat(newRestaurant(report, "b1")).satisfies(n -> {
            assertThat(n.getFoodKeys()).contains("banh-mi").doesNotContain("mi");   // "bánh mì" is not mì
            assertThat(n.getRelevance()).isEqualTo("OTHER_FOOD");
            assertThat(n.getClassifierDisagrees()).isTrue();
        });
        assertThat(report.getQueries().get(0).getFoodRelevantNew()).isZero();
    }

    @Test
    void cellIngestionIsUnaffectedByTheRelevanceGate() {
        goong.predict("restaurant", centerA, "x1");
        goong.details.put("x1", place("x1", "Cửa hàng Minh Anh", centerA[0] + 0.0005, centerA[1]));

        SnapshotRunReport report = run(p -> { });

        assertThat(saved).extracting(Restaurant::getGoongPlaceId).containsExactly("x1");
        assertThat(report.getRejected()).isEmpty();
        assertThat(newRestaurant(report, "x1")).satisfies(n -> {
            assertThat(n.getRelevance()).isNull();
            assertThat(n.getMatchesProbeFood()).isNull();
            assertThat(n.getClassifierDisagrees()).isNull();
        });
        assertThat(report.getQueries()).allSatisfy(q -> assertThat(q.getRelevantPerRequest()).isNull());
    }

    @Test
    void placesSavedBeyondTheRuntimeRadiusAreCounted() {
        // the save region is the area radius + 500 m; the app shows the area within its radius
        goong.predict("Phở", BT, "in", "out");
        goong.details.put("in", place("in", "Phở Gần", BT[0] + 0.010, BT[1]));    // ~1.1 km
        goong.details.put("out", place("out", "Phở Xa", BT[0] + 0.025, BT[1]));   // ~2.8 km

        SnapshotRunReport report = runFood(p -> p.setFoods(new ArrayList<>(List.of("pho"))));

        assertThat(saved).hasSize(2);
        assertThat(newRestaurant(report, "in").getInsideRuntimeRadius()).isTrue();
        assertThat(newRestaurant(report, "out").getInsideRuntimeRadius()).isFalse();
        assertThat(report.getQueries().get(0).getSavedOutsideRuntimeRadius()).isEqualTo(1);
    }

    @Test
    void newRestaurantsCarryReportOnlyDataQualityFlags() {
        known.add(row(7L, "k1", "Phở Lý", "12 Lê Quang Định, Bình Thạnh", BT[0] + 0.010, BT[1]));
        goong.predict("Phở", BT, "ward", "street", "near", "comma");
        goong.details.put("ward", dto("ward", "Phở Hồng", "Phở Hồng, Phường 11, Bình Thạnh, Hồ Chí Minh", BT[0] - 0.004));
        goong.details.put("street", dto("street", "Phở Lý", "Phở Lý, Đường Bạch Đằng, Bình Thạnh", BT[0] - 0.006));
        goong.details.put("near", dto("near", "Phở Mới", "45 Lê Quang Định, Bình Thạnh", BT[0] + 0.0101));   // ~11 m from k1
        goong.details.put("comma", dto("comma", "Phở Hà, Chi nhánh 2", "Phở Hà, Chi nhánh 2, 9 Nơ Trang Long, Bình Thạnh", BT[0] - 0.008));

        SnapshotRunReport report = runFood(p -> p.setFoods(new ArrayList<>(List.of("pho"))));

        assertThat(newRestaurant(report, "ward").getDataQualityFlags()).containsExactly("ADDRESS_WARD_ONLY");
        assertThat(newRestaurant(report, "street").getDataQualityFlags())
            .containsExactly("ADDRESS_NO_HOUSE_NUMBER", "SAME_NAME_AS_KNOWN");
        assertThat(newRestaurant(report, "near").getDataQualityFlags()).containsExactly("NEAR_EXISTING_25M");
        assertThat(newRestaurant(report, "comma").getDataQualityFlags()).isEmpty();   // the name's own comma is skipped
        assertThat(saved).hasSize(4);   // flags never block a save
    }

    static GoongPlaceDTO dto(String id, String name, String address, double lat) {
        return new GoongPlaceDTO(id, name, address, new GoongGeometry(new GoongLocation(lat, BT[1])),
            List.of("restaurant"), null, null, null, null, null, null);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    void assertRefused(Consumer<SnapshotProperties> tweak, String why) {
        SnapshotRunReport report = run(tweak);
        assertThat(report.getStopReason()).as(why).isEqualTo(StopReason.REFUSED);
        assertThat(report.getStopDetail()).contains(why);
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
    }

    static GoongPlaceDTO place(String id, String name, double lat, double lng) {
        return new GoongPlaceDTO(id, name, name + ", 1 Test Street", new GoongGeometry(new GoongLocation(lat, lng)),
            List.of("restaurant"), null, null, null, null, null, null);
    }

    static KnownPlaceRow row(Long id, String placeId, String name, String address, Double lat, Double lng) {
        return new KnownPlaceRow() {
            public Long getId() { return id; }
            public String getGoongPlaceId() { return placeId; }
            public String getName() { return name; }
            public String getAddress() { return address; }
            public Double getLatitude() { return lat; }
            public Double getLongitude() { return lng; }
        };
    }

    /** Scripted Goong that charges the budget per request, like GoongClientImpl. */
    static final class FakeGoong implements GoongClient {
        final GoongRequestBudget budget;
        final Map<String, List<GoongPrediction>> predictions = new HashMap<>();
        final Map<String, Object> details = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Integer> radii = new ArrayList<>();
        /** Inputs whose Autocomplete needs a retry: two charged attempts. */
        final java.util.Set<String> retryOnce = new java.util.HashSet<>();
        /** Runs once, just before the first Goong request of the run. */
        Runnable beforeFirstRequest;

        private void first() {
            if (beforeFirstRequest != null) {
                Runnable r = beforeFirstRequest;
                beforeFirstRequest = null;
                r.run();
            }
        }

        FakeGoong(GoongRequestBudget budget) {
            this.budget = budget;
        }

        String key(String keyword, double[] center) {
            return keyword + "@" + String.format(Locale.ROOT, "%.6f,%.6f", center[0], center[1]);
        }

        void predict(String keyword, double[] center, String... ids) {
            predictions.put(key(keyword, center),
                Stream.of(ids).map(id -> new GoongPrediction(id, "desc " + id)).toList());
        }

        /** A place whose name the server classifies as phở (food probes save only catalog foods). */
        void place(String id, double[] center, double northOffsetDeg) {
            details.put(id, SnapshotIngestionServiceTest.place(id, "Phở " + id, center[0] + northOffsetDeg, center[1]));
        }

        List<String> detailCalls() {
            return calls.stream().filter(c -> c.startsWith("PD ")).map(c -> c.substring(3)).toList();
        }

        List<String> autocompleteCalls() {
            return calls.stream().filter(c -> c.startsWith("AC ")).toList();
        }

        @Override
        public List<GoongPrediction> autoComplete(String input, double lat, double lng, int radiusMeters) {
            first();
            budget.charge("autoComplete", 1);
            if (retryOnce.contains(input)) budget.charge("autoComplete", 2);
            calls.add("AC " + input);
            radii.add(radiusMeters);
            return predictions.getOrDefault(key(input, new double[]{lat, lng}), List.of());
        }

        /** Runs once, just before the first Place Detail request (after the Autocomplete phase). */
        Runnable beforeFirstDetail;

        @Override
        public Optional<GoongPlaceDTO> placeDetail(String goongPlaceId) {
            first();
            if (beforeFirstDetail != null) {
                Runnable r = beforeFirstDetail;
                beforeFirstDetail = null;
                r.run();
            }
            budget.charge("placeDetail", 1);
            calls.add("PD " + goongPlaceId);
            Object d = details.get(goongPlaceId);
            if (d instanceof RuntimeException e) throw e;
            return Optional.ofNullable((GoongPlaceDTO) d);
        }
    }
}
