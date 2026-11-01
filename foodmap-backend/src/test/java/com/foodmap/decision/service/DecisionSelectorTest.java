package com.foodmap.decision.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Weighted draw among the top-N — tested with controlled randomness. */
class DecisionSelectorTest {

    private static final List<String> RANKED =
        IntStream.rangeClosed(1, 15).mapToObj(i -> "r" + i).toList();

    @Test
    void pool_isTopN_inRankOrder() {
        assertThat(DecisionSelector.pool(RANKED, 10)).containsExactly(
            "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10");
        assertThat(DecisionSelector.pool(List.of("a", "b"), 10)).containsExactly("a", "b");
    }

    @Test
    void weights_fallLinearlyWithRank() {
        assertThat(IntStream.range(0, 4).map(i -> DecisionSelector.weight(i, 4)).toArray())
            .containsExactly(4, 3, 2, 1);
    }

    @Test
    void tickets_mapToCandidatesByCumulativeWeight() {
        // pool of 4 → weights 4,3,2,1 → tickets 0-3 → a, 4-6 → b, 7-8 → c, 9 → d
        List<String> pool = List.of("a", "b", "c", "d");
        assertThat(DecisionSelector.pickWithTicket(pool, 0)).isEqualTo("a");
        assertThat(DecisionSelector.pickWithTicket(pool, 3)).isEqualTo("a");
        assertThat(DecisionSelector.pickWithTicket(pool, 4)).isEqualTo("b");
        assertThat(DecisionSelector.pickWithTicket(pool, 6)).isEqualTo("b");
        assertThat(DecisionSelector.pickWithTicket(pool, 7)).isEqualTo("c");
        assertThat(DecisionSelector.pickWithTicket(pool, 9)).isEqualTo("d");
        assertThatThrownBy(() -> DecisionSelector.pickWithTicket(pool, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pick_alwaysComesFromThePool() {
        DecisionSelector selector = new DecisionSelector(new Random(42));
        List<String> pool = DecisionSelector.pool(RANKED, 10);
        for (int i = 0; i < 2_000; i++) {
            assertThat(pool).contains(selector.pick(RANKED, 10));
        }
    }

    @Test
    void pick_favoursHigherRanks_withoutAlwaysReturningTheTop() {
        DecisionSelector selector = new DecisionSelector(new Random(7));
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 5_500; i++) counts.merge(selector.pick(RANKED, 10), 1, Integer::sum);
        // Expected shares: r1 = 10/55 ≈ 18%, r10 = 1/55 ≈ 1.8%
        assertThat(counts.get("r1")).isGreaterThan(counts.get("r5")).isGreaterThan(counts.get("r10"));
        assertThat(counts.keySet()).hasSize(10);
    }

    @Test
    void singleCandidate_isAlwaysPicked() {
        assertThat(new DecisionSelector(new Random(1)).pick(List.of("only"), 10)).isEqualTo("only");
    }

    @Test
    void emptyList_isRejected() {
        assertThatThrownBy(() -> new DecisionSelector().pick(List.of(), 10))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
