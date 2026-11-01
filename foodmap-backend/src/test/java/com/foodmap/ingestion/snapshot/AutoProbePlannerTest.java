package com.foodmap.ingestion.snapshot;

import com.foodmap.catalog.AreaPreset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The AUTO planner: eligibility, tiers, the approved score, waves. Pure — no I/O. */
class AutoProbePlannerTest {

    // alpha and beta overlap (1.3 km apart, 2 km radii); gamma is far from both.
    static final AreaPreset ALPHA = new AreaPreset("alpha", "Alpha", 10, 10.800, 106.700, 2000);
    static final AreaPreset BETA = new AreaPreset("beta", "Beta", 20, 10.812, 106.700, 2000);
    static final AreaPreset GAMMA = new AreaPreset("gamma", "Gamma", 30, 10.700, 106.600, 2000);
    static final List<AreaPreset> AREAS = List.of(ALPHA, BETA, GAMMA);

    static final List<AutoProbePlanner.Food> FOODS = List.of(
        new AutoProbePlanner.Food("tra-sua", "DRINK", 20),
        new AutoProbePlanner.Food("bun", "MEAL", 30),
        new AutoProbePlanner.Food("pho", "MEAL", 50),
        new AutoProbePlanner.Food("mi", "MEAL", 70),
        new AutoProbePlanner.Food("com", "MEAL", 80),
        new AutoProbePlanner.Food("che", "DESSERT", 190));

    final AutoProbePlanner planner = new AutoProbePlanner();
    final Map<String, Map<String, Integer>> coverage = new HashMap<>();
    Set<String> completed = Set.of();
    Set<String> pending = Set.of();
    Set<String> probedThisRun = Set.of();
    Set<String> allowed = Set.of();
    Set<String> excluded = Set.of("mi");
    ProbeHistory history = ProbeHistory.of(List.of(
        ProbeHistoryTest.probe("bun", 5, 4, 0, 0), ProbeHistoryTest.probe("pho", 5, 4, 0, 0),
        ProbeHistoryTest.probe("com", 5, 4, 0, 0), ProbeHistoryTest.probe("che", 5, 4, 0, 0),
        ProbeHistoryTest.probe("tra-sua", 5, 4, 0, 0)));
    double minScore = 0.25;

    void count(String area, String food, int n) {
        coverage.computeIfAbsent(area, k -> new HashMap<>()).put(food, n);
    }

    AutoProbePlanner.Input input() {
        return new AutoProbePlanner.Input(AREAS, allowed, FOODS, excluded, coverage, completed, pending, probedThisRun,
            history, 3, minScore);
    }

    AutoProbePlanner.Ranking rank() {
        return planner.rank(input());
    }

    List<String> order() {
        return rank().eligible().stream().map(AutoProbePlanner.Scored::pair).toList();
    }

    AutoRunReport.Candidate candidate(String pair) {
        return rank().eligible().stream().map(AutoProbePlanner.Scored::candidate)
            .filter(c -> c.getPair().equals(pair)).findFirst().orElseThrow();
    }

    String skipReason(String pair) {
        return rank().skipped().stream().filter(s -> s.getPair().equals(pair)).map(AutoRunReport.Skipped::getReason)
            .findFirst().orElse(null);
    }

    @Test
    void zeroSupplyComesBeforeLowSupplyWhateverTheScore() {
        allowed = Set.of("gamma");
        count("gamma", "bun", 2);   // supply 1..2: band 1
        history = ProbeHistory.of(List.of(ProbeHistoryTest.probe("bun", 1, 5, 0, 0), ProbeHistoryTest.probe("pho", 5, 0, 0, 0)));
        assertThat(candidate("food:bun@gamma").getScore()).isGreaterThan(candidate("food:pho@gamma").getScore());
        assertThat(order()).containsSubsequence("food:pho@gamma", "food:bun@gamma");
        assertThat(candidate("food:bun@gamma").getTier()).isEqualTo("1/MEAL");
    }

    @Test
    void mealsComeBeforeDessertsBeforeDrinks() {
        allowed = Set.of("gamma");
        assertThat(order()).containsExactly("food:bun@gamma", "food:pho@gamma", "food:com@gamma",
            "food:che@gamma", "food:tra-sua@gamma");
    }

    @Test
    void theScoreIsTheApprovedBlendedEstimatePerExpectedRequest() {
        allowed = Set.of("gamma");
        AutoRunReport.Candidate c = candidate("food:bun@gamma");
        assertThat(c.getExpectedUseful()).isEqualTo(4.0);          // (4 + 2·4) / (1 + 2)
        assertThat(c.getExpectedCost()).isEqualTo(6.0);            // 1 + (5 + 2·5) / 3
        assertThat(c.getOverlapDiscount()).isZero();
        assertThat(c.getNeighbourMultiplier()).isEqualTo(1.0);     // gamma has no neighbour
        assertThat(c.getScore()).isEqualTo(0.667);
        assertThat(c.getFoodProbes()).isEqualTo(1);
    }

    @Test
    void earlierProbesOfTheSameFoodNearbyDiscountTheExpectedYield() {
        completed = Set.of("food:bun@beta");
        AutoRunReport.Candidate alpha = candidate("food:bun@alpha");
        double overlap = AutoProbePlanner.overlapFraction(ALPHA, BETA);
        assertThat(overlap).isBetween(0.4, 0.9);
        assertThat(alpha.getOverlapDiscount()).isCloseTo(overlap, within(0.001));
        assertThat(alpha.getExpectedUseful()).isEqualTo(4.0);
        assertThat(alpha.getScore()).isCloseTo(4.0 * (1 - 0.5 * overlap) / 6.0, within(0.001));
        assertThat(candidate("food:bun@gamma").getOverlapDiscount()).isZero();
        assertThat(candidate("food:pho@alpha").getOverlapDiscount()).isZero();   // other foods are not discounted
    }

    @Test
    void neighboursAlreadyAtTargetReduceThePriority() {
        count("beta", "com", 3);
        AutoRunReport.Candidate c = candidate("food:com@alpha");
        assertThat(c.getNeighbourMultiplier()).isEqualTo(0.5);   // alpha's only neighbour (beta) is at target
        assertThat(candidate("food:pho@alpha").getNeighbourMultiplier()).isEqualTo(1.0);
        assertThat(skipReason("food:com@beta")).isEqualTo("at target (3 ≥ 3)");
    }

    @Test
    void completedPendingExhaustedExcludedAndProbedPairsAreNeverCandidates() {
        completed = Set.of("food:bun@alpha", "food:pho@alpha");
        count("alpha", "pho", 1);   // completed below target = exhausted
        pending = Set.of("food:com@alpha");
        probedThisRun = Set.of("food:che@alpha");
        assertThat(skipReason("food:bun@alpha")).isEqualTo("completed by an earlier run — never re-probed (exhausted below target)");
        assertThat(skipReason("food:pho@alpha")).contains("exhausted below target");
        assertThat(skipReason("food:com@alpha")).startsWith("pending");
        assertThat(skipReason("food:che@alpha")).isEqualTo("probed earlier in this run");
        assertThat(skipReason("food:mi@alpha")).startsWith("excluded food");
        assertThat(order()).noneMatch(p -> p.endsWith("@alpha") && !p.startsWith("food:tra-sua"));
    }

    @Test
    void probesBelowTheMinimumScoreAreSkippedWithTheirScore() {
        minScore = 0.7;
        allowed = Set.of("gamma");
        assertThat(order()).isEmpty();
        assertThat(skipReason("food:bun@gamma")).isEqualTo("score 0.667 < min-score 0.7");
    }

    @Test
    void theRankingIsDeterministicWhateverTheInputOrder() {
        List<String> first = order();
        List<AreaPreset> shuffledAreas = new ArrayList<>(AREAS);
        Collections.reverse(shuffledAreas);
        List<AutoProbePlanner.Food> shuffledFoods = new ArrayList<>(FOODS);
        Collections.reverse(shuffledFoods);
        AutoProbePlanner.Ranking again = planner.rank(new AutoProbePlanner.Input(shuffledAreas, allowed, shuffledFoods,
            excluded, coverage, completed, pending, probedThisRun, history, 3, minScore));
        assertThat(again.eligible().stream().map(AutoProbePlanner.Scored::pair).toList()).isEqualTo(first);
        assertThat(order()).isEqualTo(first);
    }

    @Test
    void aWaveIsOneAreaInRankOrderWithinTheProbeLimit() {
        AutoProbePlanner.Wave wave = planner.nextWave(rank(), AREAS, null, 2).orElseThrow();
        assertThat(wave.area()).isEqualTo("alpha");
        assertThat(wave.probes()).extracting(AutoProbePlanner.Scored::pair).containsExactly("food:bun@alpha", "food:pho@alpha");
        assertThat(wave.reason()).contains("best candidate food:bun@alpha");
        assertThat(planner.nextWave(rank(), AREAS, null, 0)).isEmpty();
    }

    @Test
    void neighboursOfThePreviousWaveSitOutOneWave() {
        allowed = Set.of("beta", "gamma");
        // beta ranks first (area order) — but alpha's wave just ran and covers beta's save circle
        assertThat(AutoProbePlanner.cooledDown(AREAS, "alpha")).containsExactly("beta");
        AutoProbePlanner.Wave wave = planner.nextWave(rank(), AREAS, "alpha", 6).orElseThrow();
        assertThat(wave.area()).isEqualTo("gamma");
        assertThat(wave.reason()).contains("cooled down after alpha: beta");

        allowed = Set.of("beta");   // nothing else eligible: the cool-down is waived
        AutoProbePlanner.Wave waived = planner.nextWave(rank(), AREAS, "alpha", 6).orElseThrow();
        assertThat(waived.area()).isEqualTo("beta");
        assertThat(waived.reason()).contains("waived");
    }

    @Test
    void atMostOneNeverProbedFoodPerWave() {
        history = ProbeHistory.of(List.of(ProbeHistoryTest.probe("che", 5, 4, 0, 0)));   // bun, pho, com untested
        allowed = Set.of("gamma");
        AutoProbePlanner.Wave wave = planner.nextWave(rank(), AREAS, null, 6).orElseThrow();
        assertThat(wave.probes()).extracting(AutoProbePlanner.Scored::pair)
            .containsExactly("food:bun@gamma", "food:che@gamma");
    }

    @Test
    void geometry() {
        assertThat(AutoProbePlanner.lensArea(100, 100, 0)).isCloseTo(Math.PI * 100 * 100, within(1e-6));
        assertThat(AutoProbePlanner.lensArea(100, 100, 250)).isZero();
        assertThat(AutoProbePlanner.lensArea(100, 50, 10)).isCloseTo(Math.PI * 50 * 50, within(1e-6));
        assertThat(AutoProbePlanner.overlapFraction(ALPHA, ALPHA)).isCloseTo(1.0, within(1e-9));
        assertThat(AutoProbePlanner.overlapFraction(ALPHA, GAMMA)).isZero();
        assertThat(AutoProbePlanner.intersects(ALPHA, BETA)).isTrue();
        assertThat(AutoProbePlanner.intersects(ALPHA, GAMMA)).isFalse();
        assertThat(AutoProbePlanner.parsePair("food:bun-bo@quan-5")).containsExactly("bun-bo", "quan-5");
        assertThat(AutoProbePlanner.parsePair("w3gvkq")).isNull();
    }
}
