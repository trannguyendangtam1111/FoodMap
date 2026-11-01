package com.foodmap.ingestion.snapshot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoBudgetTest {

    @Test
    void theEffectiveBudgetFitsTheRollingLedger() {
        assertThat(AutoBudget.effective(40, 120, 0)).isEqualTo(40);
        assertThat(AutoBudget.effective(120, 120, 114)).isEqualTo(6);   // 114 used → 6 left today
        assertThat(AutoBudget.effective(300, 120, 0)).isEqualTo(120);   // never above the ceiling
        assertThat(AutoBudget.effective(40, 120, 130)).isZero();
    }

    @Test
    void oneProbeNeedsSixRequestsPlusTheRetryMargin() {
        assertThat(AutoBudget.WORST_CASE_PER_PROBE).isEqualTo(6);
        assertThat(AutoBudget.retryMargin(6)).isEqualTo(2);
        assertThat(AutoBudget.retryMargin(40)).isEqualTo(2);
        assertThat(AutoBudget.retryMargin(120)).isEqualTo(6);
        assertThat(AutoBudget.fitsOneProbe(6)).isFalse();    // ledger at 114/120: LEDGER_FULL
        assertThat(AutoBudget.fitsOneProbe(7)).isFalse();
        assertThat(AutoBudget.fitsOneProbe(8)).isTrue();
        assertThat(AutoBudget.fitsOneProbe(0)).isFalse();
    }

    @Test
    void wavesAreSizedForTheWorstCase() {
        assertThat(AutoBudget.probesThatFit(40, 2)).isEqualTo(6);
        assertThat(AutoBudget.probesThatFit(120, 6)).isEqualTo(19);
        assertThat(AutoBudget.probesThatFit(13, 2)).isEqualTo(1);
        assertThat(AutoBudget.probesThatFit(7, 2)).isZero();
        assertThat(AutoBudget.probesThatFit(1, 2)).isZero();
        assertThat(AutoBudget.worstCaseResume(1, 3)).isEqualTo(9);   // one query left (6) + 3 place_ids
        assertThat(AutoBudget.worstCaseResume(0, 2)).isEqualTo(2);
    }
}
