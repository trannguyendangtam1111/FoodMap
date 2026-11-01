package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.exception.GoongRunAbortedException;
import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.AppProperties;
import com.foodmap.config.properties.GoongProperties;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.food.match.FoodMatcher;
import com.foodmap.ingestion.CellCoverageService;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import com.foodmap.ingestion.snapshot.SnapshotIngestionServiceTest.FakeGoong;
import com.foodmap.ingestion.snapshot.SnapshotRunReport.StopReason;
import com.foodmap.integration.goong.GoongRequestBudget;
import com.foodmap.integration.goong.mapper.GoongPlaceMapper;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.KnownPlaceRow;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.restaurant.service.RestaurantSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.foodmap.ingestion.snapshot.SnapshotIngestionServiceTest.place;
import static com.foodmap.ingestion.snapshot.SnapshotIngestionServiceTest.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTO runs end to end with a fake Goong (charging the real budget per
 * request), mocked repositories, the real catalog and classifier, a coverage
 * reader that counts the fake database exactly like the product (classified
 * name inside the area radius) and a fake database guard. No real Goong
 * request, no database.
 */
class AutoRunCoordinatorTest {

    // alpha and beta overlap (1.3 km apart); gamma is far from both.
    static final AreaPreset ALPHA = new AreaPreset("alpha", "Alpha", 10, 10.800, 106.700, 2000);
    static final AreaPreset BETA = new AreaPreset("beta", "Beta", 20, 10.812, 106.700, 2000);
    static final AreaPreset GAMMA = new AreaPreset("gamma", "Gamma", 30, 10.700, 106.600, 2000);
    static final double[] A = {ALPHA.centerLat(), ALPHA.centerLng()};

    static final FoodVocabulary CATALOG = FoodCatalogLoader.loadFromClasspath();

    @TempDir Path reports;

    final GoongRequestBudget budget = new GoongRequestBudget();
    final FakeGoong goong = new FakeGoong(budget);
    final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    final PlaceCellRepository placeCells = mock(PlaceCellRepository.class);
    final IngestionProperties ingestion = new IngestionProperties();
    final CellCoverageService cellCoverage = new CellCoverageService(placeCells, ingestion);
    final AreaPresets areas = mock(AreaPresets.class);
    final FoodClassifier classifier = new FoodClassifier(new FoodMatcher(CATALOG), mock(RestaurantFoodRepository.class));
    final List<KnownPlaceRow> known = new ArrayList<>();
    final List<Restaurant> saved = new ArrayList<>();
    final AtomicInteger coverageReads = new AtomicInteger();
    boolean lockFree = true;
    int locksTaken;
    String dbIdentity = "localhost:5434/foodmap_dev";
    /** Rows another writer adds behind the run's back (the DB guard must notice). */
    long foreignRows;

    @BeforeEach
    void setUp() {
        List<AreaPreset> all = List.of(ALPHA, BETA, GAMMA);
        when(areas.all()).thenReturn(all);
        all.forEach(a -> when(areas.get(a.key())).thenReturn(Optional.of(a)));
        when(restaurants.findKnownPlaces()).thenAnswer(inv -> known);
        when(restaurants.findByGoongPlaceId(anyString())).thenAnswer(inv -> known.stream()
            .filter(k -> k.getGoongPlaceId().equals(inv.getArgument(0))).findFirst()
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
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    /** Counts the fake database as FoodAvailabilityService does: classified name inside the area radius. */
    final CoverageReader coverageReader = (list, foods) -> {
        coverageReads.incrementAndGet();
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (AreaPreset area : list) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            foods.forEach(f -> counts.put(f, 0));
            for (Object[] r : stored()) {
                double d = GeoUtils.distanceMeters(area.centerLat(), area.centerLng(), (Double) r[1], (Double) r[2]);
                if (d > area.radiusMeters()) continue;
                for (String f : classifier.classify((String) r[0], null, List.of()).foodKeys()) {
                    if (counts.containsKey(f)) counts.merge(f, 1, Integer::sum);
                }
            }
            out.put(area.key(), counts);
        }
        return out;
    };

    List<Object[]> stored() {
        List<Object[]> all = new ArrayList<>();
        known.forEach(k -> all.add(new Object[]{k.getName(), k.getLatitude(), k.getLongitude()}));
        saved.forEach(s -> all.add(new Object[]{s.getName(), s.getLatitude(), s.getLongitude()}));
        return all;
    }

    final SnapshotDbGuard dbGuard = new SnapshotDbGuard() {
        @Override public String identity() { return dbIdentity; }

        @Override public Baseline baseline() {
            return new Baseline(Map.of("restaurants", (long) stored().size(), "place_cells", 0L), 0, Instant.now());
        }

        @Override public List<String> verify(Baseline b, int created, int rekeyed) {
            long now = stored().size() + foreignRows;
            long expected = b.tableCounts().get("restaurants") + created;
            return now == expected ? List.of() : List.of("restaurants has " + now + " rows, expected " + expected);
        }
    };

    AutoRunCoordinator coordinator() {
        SnapshotPlanner planner = new SnapshotPlanner(areas, placeCells, cellCoverage, ingestion, new SnapshotFoodQueries(CATALOG));
        RestaurantSyncService sync = new RestaurantSyncService(goong,
            new GoongPlaceMapper(new ObjectMapper(), new GoongProperties()), restaurants, new AppProperties());
        SnapshotRunLock lock = () -> {
            if (!lockFree) return Optional.empty();
            locksTaken++;
            return Optional.of(() -> { });
        };
        SnapshotIngestionService service = new SnapshotIngestionService(planner, goong, budget, sync, restaurants,
            cellCoverage, ingestion, lock, new ObjectMapper(), classifier);
        return new AutoRunCoordinator(service, budget, lock, new ObjectMapper(), areas, CATALOG, coverageReader,
            dbGuard, ingestion);
    }

    SnapshotProperties props(Consumer<SnapshotProperties> tweak) {
        SnapshotProperties p = new SnapshotProperties();
        p.setAuto(true);
        p.setBudget(40);
        p.setExecute(true);
        p.setReportDir(reports.toString());
        tweak.accept(p);
        return p;
    }

    /** Only {@code foods} may be probed (every other discoverable food excluded). */
    static AutoProperties only(String... foods) {
        AutoProperties a = new AutoProperties();
        List<String> keep = List.of(foods);
        a.setExcludeFoods(new ArrayList<>(CATALOG.discoverable().stream().map(FoodEntry::key)
            .filter(k -> !keep.contains(k)).toList()));
        return a;
    }

    SnapshotRunReport run(Consumer<SnapshotProperties> tweak, AutoProperties a) {
        return coordinator().run(props(tweak), a, List.of());
    }

    /** History: an executed run two days ago probing {@code foods} in an area that is not a preset (4 useful of 5 each). */
    void seedHistory(String... foods) {
        SnapshotRunReport r = new SnapshotRunReport();
        r.setRunId("seed");
        r.setMode(SnapshotRunReport.Mode.EXECUTE);
        r.setDiscovery(SnapshotRunReport.Discovery.FOOD_PROBES);
        r.setStopReason(StopReason.COMPLETED);
        r.setBudget(40);
        r.setStartedAt(Instant.now().minus(Duration.ofHours(49)).toString());
        r.setFinishedAt(Instant.now().minus(Duration.ofHours(48)).toString());
        for (String f : foods) ProbeHistoryTest.addProbe(r, f, "delta", 5, 4, 0, 0);
        r.getRequests().setUsed(6 * foods.length);
        write("seed.json", r);
    }

    /** A run that used {@code used} requests an hour ago (counts in the 24 h ledger). */
    void recentUsage(int used) {
        SnapshotRunReport r = new SnapshotRunReport();
        r.setRunId("recent");
        r.setMode(SnapshotRunReport.Mode.EXECUTE);
        r.setStopReason(StopReason.COMPLETED);
        r.setBudget(used);
        r.setStartedAt(Instant.now().minus(Duration.ofMinutes(70)).toString());
        r.setFinishedAt(Instant.now().minus(Duration.ofMinutes(60)).toString());
        r.getRequests().setUsed(used);
        write("recent.json", r);
    }

    void write(String name, SnapshotRunReport r) {
        try {
            new ObjectMapper().writeValue(reports.resolve(name).toFile(), r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<Path> runReports() {
        try (Stream<Path> s = Files.list(reports)) {
            return s.filter(f -> f.getFileName().toString().startsWith("snapshot-") && f.toString().endsWith(".json"))
                .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    SnapshotRunReport readReport(Path f) {
        try {
            return new ObjectMapper().readValue(f.toFile(), SnapshotRunReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String fileText(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void remember(List<Restaurant> rows) {
        rows.forEach(r -> known.add(row(r.getId(), r.getGoongPlaceId(), r.getName(), r.getAddress(), r.getLatitude(), r.getLongitude())));
        saved.removeAll(rows);
    }

    // ── Dry run ─────────────────────────────────────────────────────────────

    @Test
    void aDryRunRanksAndPlansWithZeroGoongRequestsAndNoWrites() {
        seedHistory("bun", "pho", "com");
        AutoProperties a = only("bun", "pho", "com", "chao");
        a.setAreas(List.of("alpha"));

        SnapshotRunReport report = run(p -> p.setExecute(false), a);

        assertThat(report.getStopReason()).isEqualTo(StopReason.DRY_RUN);
        assertThat(report.getStopDetail()).contains("execution would be allowed (effective budget 40)");
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
        verify(restaurants, never()).save(any());
        AutoRunReport auto = report.getAuto();
        assertThat(auto.getConfig().getEffectiveBudget()).isEqualTo(40);
        assertThat(auto.getConfig().getRetryMargin()).isEqualTo(2);
        assertThat(auto.getCandidates()).extracting(AutoRunReport.Candidate::getPair)
            .containsExactly("food:bun@alpha", "food:pho@alpha", "food:com@alpha", "food:chao@alpha");
        assertThat(auto.getHistory().get("bun").getProbes()).isEqualTo(1);
        assertThat(auto.getHistory().get("bun").getBlendedUseful()).isEqualTo(4.0);
        // one wave of alpha; chao (never probed) is the wave's one exploration probe
        assertThat(auto.getPlannedWaves()).singleElement().satisfies(w -> {
            assertThat(w.getArea()).isEqualTo("alpha");
            assertThat(w.getProbes()).extracting(pr -> pr.getPlanned().getPair())
                .containsExactly("food:bun@alpha", "food:pho@alpha", "food:com@alpha", "food:chao@alpha");
        });
        assertThat(auto.getEstimatedMaxRequests()).isEqualTo(24);
        assertThat(auto.getProjectedLedgerUsage()).isEqualTo(40);
        assertThat(auto.getSkipped()).anySatisfy(s -> assertThat(s.getReason()).startsWith("excluded food"));
        // one report (+ its .md); the ledger reads it, unchanged usage
        assertThat(runReports()).singleElement();
        assertThat(Files.exists(reports.resolve(auto.getSummaryFile()))).isTrue();
        SnapshotLedger.State ledger = new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now());
        assertThat(ledger.unreadable()).isEmpty();
        assertThat(ledger.usageInWindow()).isZero();
    }

    @Test
    void theLedgerAt114Leaves6RequestsWhichIsLessThanOneProbe() {
        recentUsage(114);

        SnapshotRunReport dry = run(p -> { p.setExecute(false); p.setBudget(120); p.setLarge(true); }, only("bun"));
        assertThat(dry.getAuto().getConfig().getEffectiveBudget()).isEqualTo(6);
        assertThat(dry.getStopDetail()).contains("would stop with LEDGER_FULL");
        assertThat(dry.getAuto().getPlannedWaves()).isEmpty();

        SnapshotRunReport report = run(p -> { p.setBudget(120); p.setLarge(true); }, only("bun"));
        assertThat(report.getStopReason()).isEqualTo(StopReason.LEDGER_FULL);
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
        assertThat(SnapshotIngestionRunner.exitCode(report)).isZero();
    }

    @Test
    void theEffectiveBudgetNeverExceedsTheCeiling() {
        recentUsage(100);
        SnapshotRunReport report = run(p -> { p.setBudget(120); p.setLarge(true); }, only("bun", "pho", "com"));
        assertThat(report.getAuto().getConfig().getEffectiveBudget()).isEqualTo(20);
        assertThat(report.getBudget()).isEqualTo(20);   // armed limit = what a crashed run would count
        assertThat(report.getRequests().getUsed()).isLessThanOrEqualTo(20);
        assertThat(new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now()).usageInWindow())
            .isLessThanOrEqualTo(120);
    }

    // ── Waves ───────────────────────────────────────────────────────────────

    @Test
    void multipleWavesEachReMeasureCoverage() {
        seedHistory("bun", "pho");
        AutoProperties a = only("bun", "pho");
        a.setAreas(List.of("alpha", "gamma"));
        a.setWaveSize(1);

        SnapshotRunReport report = run(p -> { }, a);

        // one probe per wave; no Goong predictions configured, so each costs 1 Autocomplete
        assertThat(report.getAuto().getWaves()).extracting(AutoRunReport.Wave::getArea)
            .containsExactly("alpha", "gamma", "alpha", "gamma");
        assertThat(goong.autocompleteCalls()).containsExactly("AC Bún", "AC Bún", "AC Phở", "AC Phở");
        assertThat(coverageReads.get()).isEqualTo(1 + 4);   // before + after every wave
        assertThat(report.getStopReason()).isEqualTo(StopReason.NO_ELIGIBLE_WORK);
        assertThat(report.getCompletedProbes()).containsExactlyInAnyOrder(
            "food:bun@alpha", "food:bun@gamma", "food:pho@alpha", "food:pho@gamma");
        assertThat(report.getAuto().getWaves()).allSatisfy(w -> assertThat(w.getDbCheck()).isEqualTo("OK"));
        assertThat(report.getRequests().getUsed()).isEqualTo(4);
        assertThat(budget.isArmed()).isFalse();
    }

    @Test
    void spillOverMeasuredAfterAWaveChangesTheNextWave() {
        seedHistory("bun");
        AutoProperties a = only("bun");
        a.setAreas(List.of("alpha", "beta"));
        // three bún places between alpha and beta: inside both areas
        goong.predict("Bún", A, "b1", "b2", "b3");
        goong.details.put("b1", place("b1", "Bún riêu Một", 10.806, 106.700));
        goong.details.put("b2", place("b2", "Bún bò Hai", 10.806, 106.701));
        goong.details.put("b3", place("b3", "Bún chả Ba", 10.806, 106.699));

        SnapshotRunReport report = run(p -> { }, a);

        assertThat(report.getAuto().getCandidates()).extracting(AutoRunReport.Candidate::getPair)
            .containsExactly("food:bun@alpha", "food:bun@beta");          // both eligible at the start
        assertThat(goong.autocompleteCalls()).containsExactly("AC Bún");     // beta was never probed
        assertThat(report.getStopReason()).isEqualTo(StopReason.NO_ELIGIBLE_WORK);
        // bún reached beta without probing it (bún bò "Hai" also raised bún bò in both areas)
        assertThat(report.getAuto().getSpillOver()).extracting(c -> c.getFood() + "@" + c.getArea() + "=" + c.getAfter())
            .contains("bun@beta=3", "bun-bo@alpha=1", "bun-bo@beta=1")
            .doesNotContain("bun@alpha=3");
        assertThat(report.getAuto().getPairsReachedTarget()).extracting(c -> c.getFood() + "@" + c.getArea())
            .contains("bun@alpha", "bun@beta");
        assertThat(report.getAuto().getWaves().get(0).getProbes().get(0)).satisfies(pr -> {
            assertThat(pr.getAutocomplete()).isEqualTo(1);
            assertThat(pr.getPlaceDetail()).isEqualTo(3);
            assertThat(pr.getRelevant()).isEqualTo(3);
            assertThat(pr.getInsideRelevant()).isEqualTo(3);
        });
    }

    @Test
    void maxProbesStopsTheRun() {
        seedHistory("bun", "pho", "com");
        AutoProperties a = only("bun", "pho", "com");
        a.setMaxProbes(2);
        SnapshotRunReport report = run(p -> { }, a);
        assertThat(goong.autocompleteCalls()).hasSize(2);
        assertThat(report.getStopReason()).isEqualTo(StopReason.MAX_PROBES_REACHED);
    }

    @Test
    void wavesAreSizedToTheRemainingBudget() {
        seedHistory("bun", "pho", "com", "chao", "hu-tieu", "banh-mi");
        AutoProperties a = only("bun", "pho", "com", "chao", "hu-tieu", "banh-mi");
        a.setAreas(List.of("gamma"));
        // budget 13: (13 − 2) / 6 = 1 probe; then 12 left → 1 more; then 11 → 1; …
        SnapshotRunReport report = run(p -> p.setBudget(13), a);
        assertThat(report.getAuto().getWaves()).allSatisfy(w -> assertThat(w.getProbes()).hasSize(1));
        assertThat(report.getRequests().getUsed()).isLessThanOrEqualTo(13);
        assertThat(report.getStopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(report.getStopDetail()).contains("below one probe");
    }

    // ── Checkpoints, resume ─────────────────────────────────────────────────

    @Test
    void discoveredIdsAreOnDiskBeforeTheFirstPlaceDetail() {
        seedHistory("bun");
        AutoProperties a = only("bun");
        a.setAreas(List.of("alpha"));
        goong.predict("Bún", A, "b1", "b2");
        goong.place("b1", A, 0.001);
        goong.place("b2", A, 0.002);
        List<SnapshotRunReport> seen = new ArrayList<>();
        List<String> pendingInLedger = new ArrayList<>();
        goong.beforeFirstDetail = () -> {
            seen.add(readReport(runReports().get(0)));
            pendingInLedger.addAll(new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now()).pendingPairs().keySet());
        };

        SnapshotRunReport report = run(p -> { }, a);

        assertThat(seen).singleElement().satisfies(r -> {
            assertThat(r.getStopReason()).isEqualTo(StopReason.IN_PROGRESS);
            assertThat(r.getPartialCells()).singleElement().satisfies(pc -> {
                assertThat(pc.getCellKey()).isEqualTo("food:bun@alpha");
                assertThat(pc.getKeywordsPending()).isEmpty();   // its Autocomplete is done
                assertThat(pc.getPendingPlaces()).extracting(SnapshotPlan.PendingPlace::placeId).containsExactly("b1", "b2");
            });
        });
        // at that moment the ledger already saw the pair as pending: a crash there is resumed, not re-probed
        assertThat(pendingInLedger).containsExactly("food:bun@alpha");
        // and once the run finished it is completed
        assertThat(report.getCompletedProbes()).containsExactly("food:bun@alpha");
        assertThat(new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now()).pendingPairs()).isEmpty();
    }

    @Test
    void aRateLimitStopsSafelyAndTheNextRunResumesWithoutRepeatingAutocomplete() {
        seedHistory("bun");
        AutoProperties a = only("bun");
        a.setAreas(List.of("alpha"));
        goong.predict("Bún", A, "f1", "f2", "f3");
        goong.place("f1", A, 0.001);
        goong.details.put("f2", new GoongRunAbortedException(GoongRunAbortedException.Reason.RATE_LIMITED, "429", null));
        goong.place("f3", A, 0.003);

        SnapshotRunReport first = run(p -> { }, a);

        assertThat(first.getStopReason()).isEqualTo(StopReason.RATE_LIMITED);
        assertThat(budget.isArmed()).isFalse();
        assertThat(first.getPartialCells()).singleElement().satisfies(pc ->
            assertThat(pc.getPendingPlaces()).extracting(SnapshotPlan.PendingPlace::placeId).containsExactly("f2", "f3"));
        assertThat(SnapshotIngestionRunner.exitCode(first)).isEqualTo(3);
        assertThat(new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now()).pendingPairs())
            .containsOnlyKeys("food:bun@alpha");

        remember(new ArrayList<>(saved));   // f1 is now stored
        goong.place("f2", A, 0.002);
        goong.calls.clear();

        SnapshotRunReport second = run(p -> { }, a);

        assertThat(goong.autocompleteCalls()).isEmpty();                 // never repeated
        assertThat(goong.detailCalls()).containsExactly("f2", "f3");    // never f1
        assertThat(second.getAuto().getResumedPairs()).containsExactly("food:bun@alpha");
        assertThat(second.getAuto().getWaves().get(0).getArea()).isEqualTo(AutoRunCoordinator.RESUME_AREA);
        assertThat(second.getCompletedProbes()).containsExactly("food:bun@alpha");
        assertThat(second.getStopReason()).isEqualTo(StopReason.NO_ELIGIBLE_WORK);
        assertThat(new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now()).pendingPairs()).isEmpty();
        assertThat(saved).extracting(Restaurant::getGoongPlaceId).containsExactly("f2", "f3");   // no duplicate row
    }

    @Test
    void aDegradedProviderStopsSafely() {
        seedHistory("bun", "pho");
        AutoProperties a = only("bun", "pho");
        a.setAreas(List.of("alpha"));
        goong.predict("Bún", A, "f1");
        goong.details.put("f1", new GoongRunAbortedException(GoongRunAbortedException.Reason.PROVIDER_DEGRADED, "degraded", null));

        SnapshotRunReport report = run(p -> { }, a);

        assertThat(report.getStopReason()).isEqualTo(StopReason.PROVIDER_DEGRADED);
        assertThat(budget.isArmed()).isFalse();
        assertThat(report.getPendingIds()).containsExactly("f1");
        assertThat(report.getAuto().getWaves()).hasSize(1);
    }

    // ── Database safety ─────────────────────────────────────────────────────

    @Test
    void anUnexpectedDatabaseChangeStopsTheRunAfterTheWave() {
        seedHistory("bun", "pho", "com");
        AutoProperties a = only("bun", "pho", "com");
        a.setWaveSize(1);
        goong.beforeFirstRequest = () -> foreignRows = 1;   // someone else writes during the run

        SnapshotRunReport report = run(p -> { }, a);

        assertThat(report.getStopReason()).isEqualTo(StopReason.DB_SAFETY);
        assertThat(report.getStopDetail()).contains("restaurants has");
        assertThat(goong.autocompleteCalls()).hasSize(1);   // no further wave
        assertThat(report.getAuto().getWaves()).singleElement()
            .satisfies(w -> assertThat(w.getDbCheck()).contains("expected"));
        assertThat(SnapshotIngestionRunner.exitCode(report)).isEqualTo(2);
    }

    @Test
    void anotherDatabaseIsRefusedBeforeTheLockAndAnyRequest() {
        dbIdentity = "db.example.com:5432/foodmap_prod";
        SnapshotRunReport report = run(p -> { }, only("bun"));
        assertThat(report.getStopReason()).isEqualTo(StopReason.DB_SAFETY);
        assertThat(report.getStopDetail()).contains("is not the allowed one localhost:5434/foodmap_dev");
        assertThat(locksTaken).isZero();
        assertThat(goong.calls).isEmpty();
    }

    // ── Guards, lock, reports ───────────────────────────────────────────────

    @Test
    void guardsRefuseBeforeAnyRequest() {
        assertRefused(p -> p.setAreas(new ArrayList<>(List.of("alpha"))), new AutoProperties(), "cannot be combined");
        assertRefused(p -> p.setFoods(new ArrayList<>(List.of("pho"))), new AutoProperties(), "cannot be combined");
        assertRefused(p -> p.setBudget(120), new AutoProperties(), "needs app.snapshot.large=true");
        assertRefused(p -> p.setBudget(null), new AutoProperties(), "budget is required");
        AutoProperties wave = new AutoProperties();
        wave.setWaveSize(7);
        assertRefused(p -> { }, wave, "wave-size must be 1..6");
        AutoProperties area = new AutoProperties();
        area.setAreas(List.of("atlantis"));
        assertRefused(p -> { }, area, "unknown area in app.snapshot.auto.areas: atlantis");
        AutoProperties food = new AutoProperties();
        food.setExcludeFoods(List.of("caviar"));
        assertRefused(p -> { }, food, "unknown food in app.snapshot.auto.exclude-foods: caviar");
        ingestion.setSeedOnStartup(true);
        assertRefused(p -> { }, new AutoProperties(), "triggers must be off");
        assertThat(locksTaken).isZero();
    }

    void assertRefused(Consumer<SnapshotProperties> tweak, AutoProperties a, String why) {
        SnapshotRunReport report = run(tweak, a);
        assertThat(report.getStopReason()).as(why).isEqualTo(StopReason.REFUSED);
        assertThat(report.getStopDetail()).contains(why);
        assertThat(goong.calls).isEmpty();
        assertThat(budget.isArmed()).isFalse();
    }

    @Test
    void anotherRunHoldingTheLockMeansNothingIsDone() {
        lockFree = false;
        SnapshotRunReport report = run(p -> { }, only("bun"));
        assertThat(report.getStopReason()).isEqualTo(StopReason.LOCKED);
        assertThat(goong.calls).isEmpty();
    }

    @Test
    void theFinalReportIsTheLedgersRecordOfTheRun() {
        seedHistory("bun", "pho");
        AutoProperties a = only("bun", "pho");
        a.setAreas(List.of("gamma"));
        goong.predict("Phở", new double[]{GAMMA.centerLat(), GAMMA.centerLng()}, "g1");
        goong.details.put("g1", place("g1", "Phở Gamma", GAMMA.centerLat() + 0.001, GAMMA.centerLng()));

        SnapshotRunReport report = run(p -> { }, a);

        assertThat(runReports()).singleElement();   // the IN_PROGRESS marker became the final report
        SnapshotRunReport onDisk = readReport(runReports().get(0));
        assertThat(onDisk.getMode()).isEqualTo(SnapshotRunReport.Mode.AUTO);
        assertThat(onDisk.getStopReason()).isEqualTo(report.getStopReason());
        assertThat(onDisk.getAuto().getWaves()).hasSize(1);
        assertThat(onDisk.getAuto().getLedgerAfter().getUsage24h()).isEqualTo(report.getRequests().getUsed());
        SnapshotLedger.State ledger = new SnapshotLedger(new ObjectMapper(), reports).read(Instant.now());
        assertThat(ledger.unreadable()).isEmpty();
        assertThat(ledger.usageInWindow()).isEqualTo(report.getRequests().getUsed());   // 2 AC + 1 PD
        assertThat(ledger.completedPairs()).contains("food:bun@gamma", "food:pho@gamma");
        String md = fileText(reports.resolve(onDisk.getAuto().getSummaryFile()));
        assertThat(md).contains("# AUTO snapshot run", "Wave 0 — gamma", "food:pho@gamma");
        verify(placeCells, never()).save(any(PlaceCell.class));
    }

    @Test
    void noSecretReachesTheReportOrItsSummary() {
        seedHistory("bun");
        AutoProperties a = only("bun");
        a.setAreas(List.of("alpha"));
        goong.predict("Bún", A, "x1");
        goong.details.put("x1", new IllegalStateException(
            "I/O error on GET request for \"https://rsapi.goong.io/place/detail?place_id=x1&api_key=SECRET-0123456789\""));

        SnapshotRunReport report = run(p -> { }, a);

        assertThat(report.getStopReason()).isEqualTo(StopReason.ERROR);
        Path file = runReports().get(0);
        assertThat(fileText(file)).doesNotContain("SECRET-0123456789").contains("api_key=***");
        assertThat(fileText(reports.resolve(readReport(file).getAuto().getSummaryFile()))).doesNotContain("SECRET-0123456789");
        assertThat(budget.isArmed()).isFalse();
    }

    @Test
    void manualModesAreUnaffected() {
        assertThat(SnapshotIngestionRunner.exitCode(StopReason.COMPLETED)).isZero();
        assertThat(SnapshotIngestionRunner.exitCode(StopReason.REFUSED)).isEqualTo(2);
        assertThat(SnapshotIngestionRunner.exitCode(StopReason.BUDGET_EXHAUSTED)).isEqualTo(3);
        assertThat(new SnapshotProperties().isAuto()).isFalse();
    }
}
