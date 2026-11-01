package com.foodmap.common.exception;

/**
 * A snapshot ingestion run must stop now: Goong answered 429, or the provider
 * health gate is DEGRADED. Only raised while a snapshot budget is armed — the
 * run stops instead of spending its budget on retries. Never retried.
 */
public class GoongRunAbortedException extends GoongIntegrationException {

    public enum Reason { RATE_LIMITED, PROVIDER_DEGRADED }

    private final Reason reason;

    public GoongRunAbortedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
