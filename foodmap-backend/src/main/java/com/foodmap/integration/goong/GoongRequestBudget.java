package com.foodmap.integration.goong;

import com.foodmap.common.exception.BudgetExhaustedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The hard Goong request budget — charged by GoongClientImpl BEFORE every
 * HTTP attempt (a retry is another request).
 *
 * Deny by default: unarmed, every charge throws BudgetExhaustedException, so
 * no code path can reach Goong. Only the snapshot ingestion runner arms it,
 * with an explicit limit (1..{@link #MAX_LIMIT}), and disarms it when done.
 *
 * An optional phase cap (e.g. discovery) limits how much of the budget one
 * phase may use, so it can never consume another phase's reserve.
 *
 * Also counts what was spent (per operation, retries, failures) for the run
 * report. Thread-safe.
 */
@Component
public class GoongRequestBudget {

    /** Hard ceiling for one run, whatever is configured. */
    public static final int MAX_LIMIT = 300;

    private boolean armed;
    private int limit;
    private int used;
    private int retries;
    private String phaseName;
    private Integer phaseCap;
    private int phaseUsed;
    private final Map<String, Integer> byOperation = new LinkedHashMap<>();
    private final Map<String, Integer> failures = new TreeMap<>();

    /** Arms the budget for one run. Refuses a second arm or a limit outside 1..MAX_LIMIT. */
    public synchronized void arm(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Goong request budget must be 1.." + MAX_LIMIT + ", was " + limit);
        }
        if (armed) throw new IllegalStateException("Goong request budget is already armed");
        this.armed = true;
        this.limit = limit;
        this.used = 0;
        this.retries = 0;
        this.phaseName = null;
        this.phaseCap = null;
        this.phaseUsed = 0;
        this.byOperation.clear();
        this.failures.clear();
    }

    /** Back to deny-by-default. Counters stay readable until the next arm. */
    public synchronized void disarm() {
        armed = false;
        limit = 0;
        phaseName = null;
        phaseCap = null;
    }

    public synchronized boolean isArmed() {
        return armed;
    }

    /** Limits the current phase to {@code cap} further requests (within the overall budget). */
    public synchronized void beginPhase(String name, int cap) {
        phaseName = name;
        phaseCap = Math.max(0, cap);
        phaseUsed = 0;
    }

    public synchronized void endPhase() {
        phaseName = null;
        phaseCap = null;
        phaseUsed = 0;
    }

    /**
     * One HTTP attempt is about to be made. Throws (and counts nothing) when
     * no budget is armed, the budget is spent, or the phase cap is reached.
     *
     * @param operation e.g. "autoComplete", "placeDetail"
     * @param attempt   1 for the first try, 2+ for retries
     */
    public synchronized void charge(String operation, int attempt) {
        if (!armed) {
            throw new BudgetExhaustedException(
                "Goong requests are disabled: no snapshot ingestion budget is armed", false);
        }
        if (used >= limit) {
            throw new BudgetExhaustedException("Goong request budget of " + limit + " is spent", false);
        }
        if (phaseCap != null && phaseUsed >= phaseCap) {
            throw new BudgetExhaustedException(
                "Goong " + phaseName + " cap of " + phaseCap + " request(s) is reached", true);
        }
        used++;
        if (phaseCap != null) phaseUsed++;
        byOperation.merge(operation, 1, Integer::sum);
        if (attempt > 1) retries++;
    }

    /** A charged attempt failed (429, 5xx:503, io, 4xx:401 …). */
    public synchronized void recordFailure(String kind) {
        failures.merge(kind, 1, Integer::sum);
    }

    public synchronized int used() {
        return used;
    }

    public synchronized int remaining() {
        return armed ? limit - used : 0;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(limit, used, Map.copyOf(byOperation), retries, Map.copyOf(failures));
    }

    public record Snapshot(int limit, int used, Map<String, Integer> byOperation, int retries, Map<String, Integer> failures) {
        public int count(String operation) {
            return byOperation.getOrDefault(operation, 0);
        }

        public int failureCount() {
            return failures.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
