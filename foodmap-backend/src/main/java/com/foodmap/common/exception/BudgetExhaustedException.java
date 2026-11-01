package com.foodmap.common.exception;

/**
 * No Goong request may be made: the snapshot run's request budget (or the
 * current phase's share of it) is spent, or no budget is armed at all —
 * outside a snapshot ingestion run Goong is denied by default.
 *
 * Thrown BEFORE the HTTP attempt, so it never costs a request. Never retried.
 */
public class BudgetExhaustedException extends GoongIntegrationException {

    private final boolean phaseLimited;

    public BudgetExhaustedException(String message, boolean phaseLimited) {
        super(message);
        this.phaseLimited = phaseLimited;
    }

    /** True when only the current phase's cap (e.g. discovery) is reached, not the whole budget. */
    public boolean isPhaseLimited() {
        return phaseLimited;
    }
}
