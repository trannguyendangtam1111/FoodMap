package com.foodmap.ingestion.snapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Food-probe yield per food, derived from the run reports only (no table, no
 * other store). One probe = one food query that ran (resumed work is not a
 * probe). Its useful restaurants = new restaurants classified with the probed
 * food (MATCH) inside the probed area's radius.
 *
 * Blended estimate (the approved model): a food's own average, pulled toward
 * the average of all probes as if that average were {@link #PRIOR_PROBES}
 * extra probes — a food tried once cannot swing to an extreme; a food never
 * tried gets the overall average:
 *
 *   expectedUseful(f) = (useful_f + k·μ) / (probes_f + k)
 *   expectedCost(f)   = 1 + (placeDetail_f + k·pd) / (probes_f + k)
 */
public final class ProbeHistory {

    public static final double PRIOR_PROBES = 2;

    /**
     * Only when no probe has ever run (an empty report directory): the averages
     * measured over the first 24 food probes (2026-10-07: 92 useful, 117 Place
     * Detail). With any history, the history's own averages are used.
     */
    static final double DEFAULT_USEFUL_PER_PROBE = 92.0 / 24;
    static final double DEFAULT_DETAIL_PER_PROBE = 117.0 / 24;

    /** One food's totals over its probes. */
    public record FoodStats(int probes, int requests, int autocomplete, int placeDetail, int relevant, int inside) {
        static final FoodStats NONE = new FoodStats(0, 0, 0, 0, 0, 0);

        FoodStats plus(int requests, int ac, int pd, int relevant, int inside) {
            return new FoodStats(probes + 1, this.requests + requests, autocomplete + ac, placeDetail + pd,
                this.relevant + relevant, this.inside + inside);
        }
    }

    private final Map<String, FoodStats> byFood;
    private final FoodStats total;

    private ProbeHistory(Map<String, FoodStats> byFood) {
        this.byFood = byFood;
        FoodStats t = FoodStats.NONE;
        for (FoodStats s : byFood.values()) {
            t = new FoodStats(t.probes() + s.probes(), t.requests() + s.requests(), t.autocomplete() + s.autocomplete(),
                t.placeDetail() + s.placeDetail(), t.relevant() + s.relevant(), t.inside() + s.inside());
        }
        this.total = t;
    }

    public static ProbeHistory empty() {
        return new ProbeHistory(new TreeMap<>());
    }

    /** From every executed food-probe report (dry runs, refusals and map-cell runs carry no probes). */
    public static ProbeHistory of(Collection<SnapshotRunReport> reports) {
        Map<String, FoodStats> byFood = new TreeMap<>();
        for (SnapshotRunReport r : reports) {
            if (r.getMode() == null || r.getMode() == SnapshotRunReport.Mode.DRY_RUN) continue;
            Map<String, Integer> radius = new LinkedHashMap<>();
            r.getPlannedCells().forEach(c -> radius.put(c.getCellKey(), c.getRadiusMeters()));
            for (SnapshotRunReport.QueryResult q : r.getQueries()) {
                if (q.getFoodKey() == null || !q.isRan() || SnapshotIngestionService.RESUMED_QUERY.equals(q.getQuery())) continue;
                int relevant = 0;
                int inside = 0;
                for (SnapshotRunReport.NewRestaurant n : r.getNewRestaurants()) {
                    if (!q.getUnitKey().equals(n.getUnitKey()) || !q.getQuery().equals(n.getQuery())) continue;
                    boolean match = Boolean.TRUE.equals(n.getMatchesProbeFood())
                        || SnapshotIngestionService.RELEVANCE_MATCH.equals(n.getRelevance());
                    if (!match) continue;
                    relevant++;
                    if (isInside(n, radius.get(n.getUnitKey()))) inside++;
                }
                int requests = q.getAutocompleteRequests() + q.getPlaceDetailRequests();
                byFood.merge(q.getFoodKey(), FoodStats.NONE.plus(requests, q.getAutocompleteRequests(),
                        q.getPlaceDetailRequests(), relevant, inside),
                    (a, b) -> a.plus(b.requests(), b.autocomplete(), b.placeDetail(), b.relevant(), b.inside()));
            }
        }
        return new ProbeHistory(byFood);
    }

    /** Reports written before insideRuntimeRadius existed: the distance against the planned radius. */
    private static boolean isInside(SnapshotRunReport.NewRestaurant n, Integer radius) {
        if (n.getInsideRuntimeRadius() != null) return n.getInsideRuntimeRadius();
        return n.getDistanceMeters() != null && radius != null && radius > 0 && n.getDistanceMeters() <= radius;
    }

    public FoodStats stats(String food) {
        return byFood.getOrDefault(food, FoodStats.NONE);
    }

    public Map<String, FoodStats> byFood() {
        return byFood;
    }

    public int totalProbes() {
        return total.probes();
    }

    /** μ: useful restaurants per probe over all probes. */
    public double globalUsefulPerProbe() {
        return total.probes() == 0 ? DEFAULT_USEFUL_PER_PROBE : (double) total.inside() / total.probes();
    }

    public double globalPlaceDetailPerProbe() {
        return total.probes() == 0 ? DEFAULT_DETAIL_PER_PROBE : (double) total.placeDetail() / total.probes();
    }

    public double expectedUseful(String food) {
        FoodStats s = stats(food);
        return (s.inside() + PRIOR_PROBES * globalUsefulPerProbe()) / (s.probes() + PRIOR_PROBES);
    }

    /** 1 Autocomplete + the blended Place Detail calls per probe. */
    public double expectedCost(String food) {
        FoodStats s = stats(food);
        return 1 + (s.placeDetail() + PRIOR_PROBES * globalPlaceDetailPerProbe()) / (s.probes() + PRIOR_PROBES);
    }
}
