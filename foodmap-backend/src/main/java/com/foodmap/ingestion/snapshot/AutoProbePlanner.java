package com.foodmap.ingestion.snapshot;

import com.foodmap.catalog.AreaPreset;
import com.foodmap.common.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses AUTO work: which food × area pairs to probe, and in which order.
 * Pure (no I/O): the coordinator passes coverage, ledger state and history.
 *
 * ELIGIBLE   a discoverable, non-excluded food in an allowed area, with fewer
 *            than {@code target} restaurants, never completed, not pending,
 *            not already probed in this run, scoring at least minScore.
 *
 * TIER       supply band, then kind: (0 restaurants, MEAL) → (0, DESSERT) →
 *            (0, DRINK) → (1..target−1, MEAL) → … (the approved product order).
 *
 * SCORE      expected useful restaurants per expected Goong request:
 *              expectedUseful(f) · (1 − ½·overlap) · neighbourMultiplier / expectedCost(f)
 *            expectedUseful / expectedCost   ProbeHistory's blended estimates
 *            overlap   share of the area's save circle already covered by save
 *                      circles of earlier probes of the same food (completed,
 *                      pending or in this run), summed, capped at 1 — weighted ½
 *                      because repeats proved rare (Q3 beside Q5: 0 known ids)
 *            neighbourMultiplier = 1 − ½ · (share of overlapping neighbour areas
 *                      that already have the food at target)
 *
 * ORDER      tier, score (high first), zero-supply MEAL pairs left in the area
 *            (more first), smaller radius, catalog order, area order — the
 *            same inputs always give the same ranking.
 *
 * WAVE       one area: the best candidate's area, skipping for one wave any
 *            other area whose save circle the previous wave's area covers by
 *            ≥ 20% (unless nothing else is eligible); its candidates in rank
 *            order, up to the probe limit, at most one never-probed food.
 */
public final class AutoProbePlanner {

    /** Save region beyond the area radius (as manual food probes: SnapshotPlanner.AREA_MARGIN_METERS). */
    static final double SAVE_MARGIN_METERS = SnapshotPlanner.AREA_MARGIN_METERS;
    static final double OVERLAP_WEIGHT = 0.5;
    static final double NEIGHBOUR_WEIGHT = 0.5;
    static final double COOLDOWN_OVERLAP = 0.2;
    static final int MAX_UNTESTED_PER_WAVE = 1;

    /** A catalog food the planner may consider: key, kind (MEAL / DESSERT / DRINK), catalog order. */
    public record Food(String key, String kind, int order) {}

    public record Input(List<AreaPreset> areas, Set<String> allowedAreas, List<Food> foods, Set<String> excluded,
                        Map<String, Map<String, Integer>> coverage, Set<String> completed, Set<String> pending,
                        Set<String> probedThisRun, ProbeHistory history, int target, double minScore) {}

    /** A ranked candidate with its sort keys. */
    public record Scored(AutoRunReport.Candidate candidate, int tierRank, int zeroMeal, int radius,
                         int catalogOrder, int areaOrder) {
        public String pair() {
            return candidate.getPair();
        }
    }

    public record Ranking(List<Scored> eligible, List<AutoRunReport.Skipped> skipped) {}

    public record Wave(String area, List<Scored> probes, String reason) {}

    public Ranking rank(Input in) {
        Map<String, AreaPreset> byKey = new HashMap<>();
        in.areas().forEach(a -> byKey.put(a.key(), a));
        Set<String> probedBefore = new LinkedHashSet<>(in.completed());
        probedBefore.addAll(in.pending());
        probedBefore.addAll(in.probedThisRun());

        List<Scored> eligible = new ArrayList<>();
        List<AutoRunReport.Skipped> skipped = new ArrayList<>();
        for (AreaPreset area : in.areas()) {
            if (!in.allowedAreas().isEmpty() && !in.allowedAreas().contains(area.key())) continue;
            int zeroMeal = zeroMeal(in, area);
            for (Food food : in.foods()) {
                String pair = SnapshotPlan.foodProbeKey(food.key(), area.key());
                int count = count(in, area.key(), food.key());
                String reason = null;
                if (in.excluded().contains(food.key())) {
                    reason = "excluded food (app.snapshot.auto.exclude-foods)";
                } else if (in.completed().contains(pair)) {
                    reason = "completed by an earlier run — never re-probed" + (count < in.target() ? " (exhausted below target)" : "");
                } else if (in.pending().contains(pair)) {
                    reason = "pending — resumed (wave 0), never re-probed";
                } else if (in.probedThisRun().contains(pair)) {
                    reason = "probed earlier in this run";
                } else if (count >= in.target()) {
                    reason = "at target (" + count + " ≥ " + in.target() + ")";
                }
                if (reason != null) {
                    skipped.add(new AutoRunReport.Skipped(pair, reason));
                    continue;
                }
                AutoRunReport.Candidate c = candidate(in, byKey, probedBefore, area, food, pair, count);
                if (c.getScore() < in.minScore()) {
                    skipped.add(new AutoRunReport.Skipped(pair, "score " + c.getScore() + " < min-score " + in.minScore()));
                    continue;
                }
                eligible.add(new Scored(c, tierRank(count, food.kind()), zeroMeal, area.radiusMeters(), food.order(), area.order()));
            }
        }
        eligible.sort(ORDER);
        return new Ranking(List.copyOf(eligible), List.copyOf(skipped));
    }

    static final Comparator<Scored> ORDER = Comparator.comparingInt(Scored::tierRank)
        .thenComparing(s -> -s.candidate().getScore())
        .thenComparing(s -> -s.zeroMeal())
        .thenComparingInt(Scored::radius)
        .thenComparingInt(Scored::catalogOrder)
        .thenComparingInt(Scored::areaOrder)
        .thenComparing(Scored::pair);

    /**
     * The next wave, or empty when nothing is eligible.
     *
     * @param previousArea the last wave's area (null at the start)
     * @param probeLimit   at most this many probes (wave size, max-probes and budget already applied)
     */
    public Optional<Wave> nextWave(Ranking ranking, List<AreaPreset> areas, String previousArea, int probeLimit) {
        if (ranking.eligible().isEmpty() || probeLimit < 1) return Optional.empty();
        Set<String> cooled = cooledDown(areas, previousArea);
        Scored first = ranking.eligible().stream()
            .filter(s -> !cooled.contains(s.candidate().getArea()))
            .findFirst()
            .orElse(null);
        boolean waived = first == null;
        if (waived) first = ranking.eligible().get(0);
        String area = first.candidate().getArea();

        List<Scored> probes = new ArrayList<>();
        int untested = 0;
        for (Scored s : ranking.eligible()) {
            if (probes.size() >= probeLimit) break;
            if (!s.candidate().getArea().equals(area)) continue;
            if (s.candidate().getFoodProbes() == 0) {
                if (untested >= MAX_UNTESTED_PER_WAVE) continue;
                untested++;
            }
            probes.add(s);
        }
        StringBuilder reason = new StringBuilder("best candidate ").append(first.pair())
            .append(" (tier ").append(first.candidate().getTier()).append(", score ").append(first.candidate().getScore())
            .append("); ").append(first.zeroMeal()).append(" zero-supply MEAL pair(s) left in ").append(area);
        if (!cooled.isEmpty()) {
            reason.append(waived ? "; neighbour cool-down waived (nothing else eligible)" : "; cooled down after "
                + previousArea + ": " + String.join(", ", cooled));
        }
        return Optional.of(new Wave(area, List.copyOf(probes), reason.toString()));
    }

    /** Areas other than the previous wave's whose save circle it covers by ≥ 20%. */
    static Set<String> cooledDown(List<AreaPreset> areas, String previousArea) {
        Set<String> out = new LinkedHashSet<>();
        if (previousArea == null) return out;
        AreaPreset prev = areas.stream().filter(a -> a.key().equals(previousArea)).findFirst().orElse(null);
        if (prev == null) return out;
        for (AreaPreset a : areas) {
            if (!a.key().equals(prev.key()) && overlapFraction(a, prev) >= COOLDOWN_OVERLAP) out.add(a.key());
        }
        return out;
    }

    private AutoRunReport.Candidate candidate(Input in, Map<String, AreaPreset> byKey, Set<String> probedBefore,
                                              AreaPreset area, Food food, String pair, int count) {
        double expected = in.history().expectedUseful(food.key());
        double cost = in.history().expectedCost(food.key());

        double overlap = 0;
        for (String other : probedBefore) {
            String[] fa = parsePair(other);
            if (fa == null || !fa[0].equals(food.key()) || fa[1].equals(area.key())) continue;
            AreaPreset b = byKey.get(fa[1]);
            if (b != null) overlap += overlapFraction(area, b);
        }
        overlap = Math.min(1, overlap);

        int neighbours = 0;
        int saturated = 0;
        for (AreaPreset b : in.areas()) {
            if (b.key().equals(area.key()) || !intersects(area, b)) continue;
            neighbours++;
            if (count(in, b.key(), food.key()) >= in.target()) saturated++;
        }
        double multiplier = neighbours == 0 ? 1 : 1 - NEIGHBOUR_WEIGHT * saturated / neighbours;
        double score = expected * (1 - OVERLAP_WEIGHT * overlap) * multiplier / cost;

        AutoRunReport.Candidate c = new AutoRunReport.Candidate();
        c.setPair(pair);
        c.setArea(area.key());
        c.setFood(food.key());
        c.setKind(food.kind());
        c.setCount(count);
        c.setTier((count == 0 ? 0 : 1) + "/" + food.kind());
        c.setFoodProbes(in.history().stats(food.key()).probes());
        c.setExpectedUseful(round(expected));
        c.setOverlapDiscount(round(overlap));
        c.setNeighbourMultiplier(round(multiplier));
        c.setExpectedCost(round(cost));
        c.setScore(round(score));
        return c;
    }

    /** MEAL foods (not excluded) still at zero in the area and not probed or pending. */
    private static int zeroMeal(Input in, AreaPreset area) {
        int n = 0;
        for (Food f : in.foods()) {
            String pair = SnapshotPlan.foodProbeKey(f.key(), area.key());
            if ("MEAL".equals(f.kind()) && !in.excluded().contains(f.key()) && count(in, area.key(), f.key()) == 0
                    && !in.completed().contains(pair) && !in.pending().contains(pair) && !in.probedThisRun().contains(pair)) {
                n++;
            }
        }
        return n;
    }

    static int tierRank(int count, String kind) {
        int band = count == 0 ? 0 : 1;
        int k = switch (kind) {
            case "MEAL" -> 0;
            case "DESSERT" -> 1;
            default -> 2;
        };
        return band * 3 + k;
    }

    private static int count(Input in, String area, String food) {
        return in.coverage().getOrDefault(area, Map.of()).getOrDefault(food, 0);
    }

    /** "food:{food}@{area}" → [food, area], or null. */
    static String[] parsePair(String pair) {
        if (pair == null || !pair.startsWith("food:")) return null;
        int at = pair.lastIndexOf('@');
        if (at < 6) return null;
        return new String[]{pair.substring(5, at), pair.substring(at + 1)};
    }

    static double saveRadius(AreaPreset a) {
        return a.radiusMeters() + SAVE_MARGIN_METERS;
    }

    static boolean intersects(AreaPreset a, AreaPreset b) {
        return GeoUtils.distanceMeters(a.centerLat(), a.centerLng(), b.centerLat(), b.centerLng()) < saveRadius(a) + saveRadius(b);
    }

    /** Share of a's save circle inside b's save circle. */
    static double overlapFraction(AreaPreset a, AreaPreset b) {
        double r1 = saveRadius(a);
        double r2 = saveRadius(b);
        double d = GeoUtils.distanceMeters(a.centerLat(), a.centerLng(), b.centerLat(), b.centerLng());
        return lensArea(r1, r2, d) / (Math.PI * r1 * r1);
    }

    /** Intersection area of two circles (radii r1, r2, centres d apart). */
    static double lensArea(double r1, double r2, double d) {
        if (d >= r1 + r2) return 0;
        if (d <= Math.abs(r1 - r2)) return Math.PI * Math.min(r1, r2) * Math.min(r1, r2);
        double a1 = r1 * r1 * Math.acos((d * d + r1 * r1 - r2 * r2) / (2 * d * r1));
        double a2 = r2 * r2 * Math.acos((d * d + r2 * r2 - r1 * r1) / (2 * d * r2));
        double k = 0.5 * Math.sqrt((-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2));
        return a1 + a2 - k;
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
