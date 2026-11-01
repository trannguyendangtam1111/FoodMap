package com.foodmap.integration.goong;

import com.foodmap.common.exception.BudgetExhaustedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The hard Goong request budget: deny by default, exact limit, phase cap, counters. */
class GoongRequestBudgetTest {

    private final GoongRequestBudget budget = new GoongRequestBudget();

    @Test
    void unarmedTheBudgetDeniesEveryRequest() {
        assertThat(budget.isArmed()).isFalse();
        assertThatThrownBy(() -> budget.charge("autoComplete", 1))
            .isInstanceOf(BudgetExhaustedException.class)
            .hasMessageContaining("no snapshot ingestion budget is armed");
        assertThat(budget.used()).isZero();
    }

    @Test
    void stopsExactlyAtTheLimit() {
        budget.arm(3);
        budget.charge("autoComplete", 1);
        budget.charge("placeDetail", 1);
        budget.charge("placeDetail", 1);
        assertThatThrownBy(() -> budget.charge("placeDetail", 1))
            .isInstanceOf(BudgetExhaustedException.class)
            .satisfies(e -> assertThat(((BudgetExhaustedException) e).isPhaseLimited()).isFalse());
        assertThat(budget.used()).isEqualTo(3);
        assertThat(budget.remaining()).isZero();
        assertThat(budget.snapshot().count("placeDetail")).isEqualTo(2);
    }

    @Test
    void aRetryIsAnotherChargedRequest() {
        budget.arm(5);
        budget.charge("autoComplete", 1);
        budget.charge("autoComplete", 2);
        assertThat(budget.used()).isEqualTo(2);
        assertThat(budget.snapshot().retries()).isEqualTo(1);
    }

    @Test
    void aPhaseCapProtectsTheRestOfTheBudget() {
        budget.arm(10);
        budget.beginPhase("discovery", 2);
        budget.charge("autoComplete", 1);
        budget.charge("autoComplete", 1);
        assertThatThrownBy(() -> budget.charge("autoComplete", 1))
            .isInstanceOf(BudgetExhaustedException.class)
            .satisfies(e -> assertThat(((BudgetExhaustedException) e).isPhaseLimited()).isTrue());
        budget.endPhase();
        for (int i = 0; i < 8; i++) budget.charge("placeDetail", 1);
        assertThat(budget.used()).isEqualTo(10);
    }

    @Test
    void limitsAreEnforcedAndArmingIsExplicitAndSingle() {
        assertThatThrownBy(() -> budget.arm(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.arm(GoongRequestBudget.MAX_LIMIT + 1)).isInstanceOf(IllegalArgumentException.class);
        budget.arm(GoongRequestBudget.MAX_LIMIT);
        assertThatThrownBy(() -> budget.arm(5)).isInstanceOf(IllegalStateException.class);
        budget.disarm();
        assertThatThrownBy(() -> budget.charge("autoComplete", 1)).isInstanceOf(BudgetExhaustedException.class);
    }
}
