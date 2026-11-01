package com.foodmap.ingestion.snapshot;

/**
 * AUTO budget arithmetic. The hard limit stays GoongRequestBudget (armed with
 * the effective budget); this only decides how much work to plan.
 *
 *   effective   B = min(configured budget, daily ceiling − usage in the last 24 h)
 *   margin      max(2, ⌈5% of B⌉) attempts kept free for retries
 *   per probe   worst case 6 attempts: 1 Autocomplete + 5 Place Detail (Goong
 *               returns at most 5 predictions per Autocomplete)
 *
 * A wave of k probes is planned only when 6·k ≤ remaining − margin; less
 * than one probe at the start = LEDGER_FULL.
 */
public final class AutoBudget {

    public static final int AUTOCOMPLETE_PER_PROBE = 1;
    public static final int MAX_DETAILS_PER_PROBE = 5;
    public static final int WORST_CASE_PER_PROBE = AUTOCOMPLETE_PER_PROBE + MAX_DETAILS_PER_PROBE;
    static final int MIN_RETRY_MARGIN = 2;
    static final double RETRY_MARGIN_SHARE = 0.05;

    private AutoBudget() {
    }

    public static int effective(int configured, int dailyCeiling, int usage24h) {
        return Math.max(0, Math.min(configured, dailyCeiling - usage24h));
    }

    public static int retryMargin(int effective) {
        return Math.max(MIN_RETRY_MARGIN, (int) Math.ceil(effective * RETRY_MARGIN_SHARE));
    }

    /** New probes the remaining budget can pay for in the worst case, the retry margin kept free. */
    public static int probesThatFit(int remaining, int margin) {
        return Math.max(0, (remaining - margin) / WORST_CASE_PER_PROBE);
    }

    /** Whether an effective budget allows at least one probe. */
    public static boolean fitsOneProbe(int effective) {
        return probesThatFit(effective, retryMargin(effective)) >= 1;
    }

    /** Worst-case cost of resuming a pending pair: its pending queries (as full probes) + its pending place_ids. */
    public static int worstCaseResume(int keywordsPending, int placesPending) {
        return keywordsPending * WORST_CASE_PER_PROBE + placesPending;
    }
}
