package com.foodmap.ingestion.snapshot;

import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.CellCoverageService;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Cell selection: deterministic, priority-ordered, within max-cells and the discovery share. */
class SnapshotPlannerTest {

    static final AreaPreset BINH_THANH = new AreaPreset("binh-thanh", "Bình Thạnh", 60, 10.8024, 106.7136, 2500);

    final AreaPresets areas = mock(AreaPresets.class);
    final PlaceCellRepository placeCells = mock(PlaceCellRepository.class);
    final IngestionProperties ingestion = new IngestionProperties();
    final CellCoverageService coverage = new CellCoverageService(placeCells, ingestion);
    final SnapshotPlanner planner = new SnapshotPlanner(areas, placeCells, coverage, ingestion,
        new SnapshotFoodQueries(FoodCatalogLoader.loadFromClasspath()));
    final List<PlaceCell> crawled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(areas.get(anyString())).thenReturn(Optional.empty());
        when(areas.get("binh-thanh")).thenReturn(Optional.of(BINH_THANH));
        when(placeCells.findAllById(any())).thenAnswer(inv -> crawled);
    }

    SnapshotProperties area(int maxCells) {
        SnapshotProperties p = new SnapshotProperties();
        p.setAreas(new ArrayList<>(List.of("binh-thanh")));
        p.setMaxCells(maxCells);
        return p;
    }

    @Test
    void neverCrawledCellsComeFirstThenSparseOrStaleAndCrawledOnlyWhenAllowed() {
        SnapshotPlan all = planner.plan(area(50), 300);
        List<String> keys = all.cells().stream().map(SnapshotPlan.Cell::cellKey).toList();
        assertThat(keys).hasSizeGreaterThan(10).doesNotHaveDuplicates();

        String dense = keys.get(0);
        String sparse = keys.get(1);
        crawled.add(PlaceCell.builder().cellKey(dense).placeCount(12).lastIndexedAt(Instant.now())
            .status(PlaceCell.Status.READY).build());
        crawled.add(PlaceCell.builder().cellKey(sparse).placeCount(1).lastIndexedAt(Instant.now())
            .status(PlaceCell.Status.READY).build());

        SnapshotPlan plan = planner.plan(area(50), 300);
        List<SnapshotPlan.Cell> cells = plan.cells();
        assertThat(cells).extracting(SnapshotPlan.Cell::cellKey).doesNotContain(dense);
        assertThat(cells.get(cells.size() - 1).cellKey()).isEqualTo(sparse);
        assertThat(cells.get(cells.size() - 1).priority()).isEqualTo(SnapshotPlan.Priority.SPARSE_OR_STALE);
        assertThat(cells.subList(0, cells.size() - 1))
            .allSatisfy(c -> assertThat(c.priority()).isEqualTo(SnapshotPlan.Priority.NEVER_CRAWLED));
        assertThat(plan.skipped()).anySatisfy(s -> assertThat(s).startsWith(dense).contains("already crawled"));

        SnapshotProperties allowCrawled = area(50);
        allowCrawled.setIncludeCrawled(true);
        assertThat(planner.plan(allowCrawled, 300).cells()).extracting(SnapshotPlan.Cell::cellKey).contains(dense);
    }

    @Test
    void aCellCrawledLongAgoIsStale() {
        String key = planner.plan(area(1), 20).cells().get(0).cellKey();
        crawled.add(PlaceCell.builder().cellKey(key).placeCount(20)
            .lastIndexedAt(Instant.now().minus(Duration.ofDays(ingestion.getFreshCooldownDays() + 1)))
            .status(PlaceCell.Status.READY).build());
        assertThat(planner.plan(area(50), 300).cells())
            .filteredOn(c -> c.cellKey().equals(key))
            .singleElement()
            .satisfies(c -> assertThat(c.priority()).isEqualTo(SnapshotPlan.Priority.SPARSE_OR_STALE));
    }

    @Test
    void thePilotIsThreeCellsAndTheOrderIsDeterministic() {
        SnapshotPlan a = planner.plan(area(3), 20);
        SnapshotPlan b = planner.plan(area(3), 20);
        assertThat(a.cells()).hasSize(3);
        assertThat(a.discoveryCalls()).isEqualTo(6);
        assertThat(a.cells()).isEqualTo(b.cells());
        assertThat(a.cells()).allSatisfy(c -> assertThat(c.keywords()).containsExactly("restaurant", "cafe"));
        assertThat(a.region()).singleElement().satisfies(c -> assertThat(c.radiusMeters()).isEqualTo(3000));
    }

    @Test
    void theDiscoveryShareOfTheBudgetLimitsTheCells() {
        assertThat(planner.plan(area(50), 5).cells()).hasSize(1);    // floor(2) / 2
        assertThat(planner.plan(area(50), 20).cells()).hasSize(4);   // floor(8) / 2
        assertThatThrownBy(() -> planner.plan(area(3), 4))           // floor(1.6) = 1 < 2
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too small");
    }

    // ── Food probes ─────────────────────────────────────────────────────────

    SnapshotProperties foods(String... keys) {
        SnapshotProperties p = new SnapshotProperties();
        p.setAreas(new ArrayList<>(List.of("binh-thanh")));
        p.setFoods(new ArrayList<>(List.of(keys)));
        return p;
    }

    @Test
    void foodProbesAreOneQueryPerFoodAtTheAreaCentreInTheGivenOrder() {
        SnapshotPlan plan = planner.planFoodProbes(foods("pho", "com", "lau", "pho"), 20);

        assertThat(plan.cells()).extracting(SnapshotPlan.Cell::cellKey)
            .containsExactly("food:pho@binh-thanh", "food:com@binh-thanh", "food:lau@binh-thanh");   // repeat dropped
        assertThat(plan.cells()).extracting(SnapshotPlan.Cell::keywords)
            .containsExactly(List.of("Phở"), List.of("Cơm"), List.of("Lẩu"));                         // catalog labels
        assertThat(plan.cells()).allSatisfy(c -> {
            assertThat(c.isFoodProbe()).isTrue();
            assertThat(c.priority()).isEqualTo(SnapshotPlan.Priority.FOOD_PROBE);
            assertThat(c.lat()).isEqualTo(BINH_THANH.centerLat());
            assertThat(c.lng()).isEqualTo(BINH_THANH.centerLng());
            assertThat(c.radiusMeters()).isEqualTo(2500);
            assertThat(c.areaKey()).isEqualTo("binh-thanh");
        });
        assertThat(plan.discoveryCalls()).isEqualTo(3);                      // no cell fan-out
        assertThat(plan.region()).singleElement().satisfies(c -> assertThat(c.radiusMeters()).isEqualTo(3000));
    }

    @Test
    void foodProbePlansAreDeterministicAndKeepTheRequestedOrder() {
        assertThat(planner.planFoodProbes(foods("pho", "com", "lau"), 20))
            .isEqualTo(planner.planFoodProbes(foods("pho", "com", "lau"), 20));
        assertThat(planner.planFoodProbes(foods("lau", "pho"), 20).cells()).extracting(SnapshotPlan.Cell::foodKey)
            .containsExactly("lau", "pho");
    }

    @Test
    void foodProbesAreRefusedRatherThanTrimmedOrGuessed() {
        assertThatThrownBy(() -> planner.planFoodProbes(foods("pho", "khong-co"), 20))
            .hasMessageContaining("not a discoverable catalog food: khong-co");
        assertThatThrownBy(() -> planner.planFoodProbes(foods("tra"), 20))   // classification-only
            .hasMessageContaining("not a discoverable catalog food: tra");
        assertThatThrownBy(() -> planner.planFoodProbes(foods("pho", "com", "lau"), 5))   // floor(2) < 3
            .hasMessageContaining("exceed the discovery share");
        SnapshotProperties twoAreas = foods("pho");
        twoAreas.setAreas(new ArrayList<>(List.of("binh-thanh", "quan-1")));
        assertThatThrownBy(() -> planner.planFoodProbes(twoAreas, 20)).hasMessageContaining("exactly one area");
    }

    @Test
    void explicitCellsAreValidatedAndComeFirstWithinTheirPriority() {
        SnapshotProperties p = area(3);
        String explicit = "w3gv7b";
        p.setCells(new ArrayList<>(List.of(explicit)));
        assertThat(planner.plan(p, 20).cells().get(0).cellKey()).isEqualTo(explicit);

        SnapshotProperties bad = new SnapshotProperties();
        bad.setCells(new ArrayList<>(List.of("not-a-cell")));
        assertThatThrownBy(() -> planner.plan(bad, 20)).hasMessageContaining("not a geohash-6 cell key");

        SnapshotProperties unknown = new SnapshotProperties();
        unknown.setAreas(new ArrayList<>(List.of("quan-99")));
        assertThatThrownBy(() -> planner.plan(unknown, 20)).hasMessageContaining("unknown area");
    }
}
