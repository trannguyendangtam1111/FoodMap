package com.foodmap.ingestion.snapshot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Yield history from report files, and the approved blended estimate. */
class ProbeHistoryTest {

    /** One executed probe of {@code food}: {@code inside} useful + {@code outside} relevant-but-outside + {@code other} other-food places. */
    static SnapshotRunReport probe(String food, int pd, int inside, int outside, int other) {
        SnapshotRunReport r = new SnapshotRunReport();
        r.setMode(SnapshotRunReport.Mode.EXECUTE);
        r.setDiscovery(SnapshotRunReport.Discovery.FOOD_PROBES);
        addProbe(r, food, "delta", pd, inside, outside, other);
        return r;
    }

    static void addProbe(SnapshotRunReport r, String food, String area, int pd, int inside, int outside, int other) {
        String unit = SnapshotPlan.foodProbeKey(food, area);
        SnapshotRunReport.QueryResult q = new SnapshotRunReport.QueryResult();
        q.setUnitKey(unit);
        q.setQuery(food.toUpperCase());
        q.setFoodKey(food);
        q.setArea(area);
        q.setRan(true);
        q.setAutocompleteRequests(1);
        q.setPlaceDetailRequests(pd);
        r.getQueries().add(q);
        r.getPlannedCells().add(new SnapshotRunReport.PlannedCell(unit, 10.8, 106.7, SnapshotPlan.Priority.FOOD_PROBE,
            List.of(q.getQuery()), 2000, food, area));
        for (int i = 0; i < inside + outside + other; i++) {
            boolean match = i < inside + outside;
            r.getNewRestaurants().add(new SnapshotRunReport.NewRestaurant((long) i, unit + i, "n", 10.8, 106.7, unit,
                q.getQuery(), i < inside ? 300 : 2500, List.of(food), match, match ? "MATCH" : "OTHER_FOOD",
                i < inside || !match, List.of(), false));
        }
        r.getCompletedProbes().add(unit);
    }

    @Test
    void noHistoryUsesTheMeasuredDefaults() {
        ProbeHistory h = ProbeHistory.empty();
        assertThat(h.totalProbes()).isZero();
        assertThat(h.globalUsefulPerProbe()).isCloseTo(92.0 / 24, within(1e-9));
        assertThat(h.expectedUseful("pho")).isCloseTo(92.0 / 24, within(1e-9));
        assertThat(h.expectedCost("pho")).isCloseTo(1 + 117.0 / 24, within(1e-9));
    }

    @Test
    void usefulMeansRelevantAndInsideTheRadius() {
        ProbeHistory h = ProbeHistory.of(List.of(probe("bun", 5, 3, 1, 1)));
        ProbeHistory.FoodStats s = h.stats("bun");
        assertThat(s.probes()).isEqualTo(1);
        assertThat(s.relevant()).isEqualTo(4);
        assertThat(s.inside()).isEqualTo(3);
        assertThat(s.requests()).isEqualTo(6);
        assertThat(s.placeDetail()).isEqualTo(5);
    }

    @Test
    void theBlendedEstimatePullsAFoodTowardTheOverallAverage() {
        // bun: 2 probes, 5 useful each; pho: 2 probes, 1 useful each → μ = 3
        ProbeHistory h = ProbeHistory.of(List.of(
            probe("bun", 5, 5, 0, 0), probe("bun", 5, 5, 0, 0), probe("pho", 5, 1, 0, 0), probe("pho", 3, 1, 0, 0)));
        assertThat(h.globalUsefulPerProbe()).isEqualTo(3.0);
        assertThat(h.expectedUseful("bun")).isCloseTo((10 + 2 * 3.0) / 4, within(1e-9));   // 4.0
        assertThat(h.expectedUseful("pho")).isCloseTo((2 + 2 * 3.0) / 4, within(1e-9));    // 2.0
        assertThat(h.expectedUseful("lau")).isEqualTo(3.0);                                // never probed: μ
        assertThat(h.globalPlaceDetailPerProbe()).isEqualTo(4.5);
        assertThat(h.expectedCost("pho")).isCloseTo(1 + (8 + 2 * 4.5) / 4, within(1e-9));
    }

    @Test
    void dryRunsResumedQueriesAndReportsWithoutTheNewFieldsAreHandled() {
        SnapshotRunReport dry = probe("bun", 5, 5, 0, 0);
        dry.setMode(SnapshotRunReport.Mode.DRY_RUN);
        SnapshotRunReport resumed = probe("com", 2, 2, 0, 0);
        resumed.getQueries().get(0).setQuery(SnapshotIngestionService.RESUMED_QUERY);
        // an older report: no relevance / insideRuntimeRadius — matchesProbeFood + distance vs the planned radius
        SnapshotRunReport old = probe("chao", 5, 0, 0, 0);
        String unit = SnapshotPlan.foodProbeKey("chao", "delta");
        old.getNewRestaurants().add(new SnapshotRunReport.NewRestaurant(1L, "c1", "Cháo", 10.8, 106.7, unit, "CHAO",
            1500, List.of("chao"), true, null, null, null, null));
        old.getNewRestaurants().add(new SnapshotRunReport.NewRestaurant(2L, "c2", "Cháo", 10.8, 106.7, unit, "CHAO",
            2400, List.of("chao"), true, null, null, null, null));

        ProbeHistory h = ProbeHistory.of(List.of(dry, resumed, old));

        assertThat(h.stats("bun").probes()).isZero();
        assertThat(h.stats("com").probes()).isZero();
        assertThat(h.stats("chao").inside()).isEqualTo(1);   // 1500 m ≤ 2000 m; 2400 m is outside
        assertThat(h.stats("chao").relevant()).isEqualTo(2);
    }
}
