package com.foodmap.integration.goong;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight provider-health gate for outbound Goong calls.
 *
 * Tracks 429 / transient failures in a rolling 60-second window. When the
 * count crosses {@link #FAILURE_THRESHOLD} the gate flips to DEGRADED:
 * subsequent {@link #shouldSkip()} calls return true for {@link #COOLDOWN_MS}
 * milliseconds. During DEGRADED:
 *   - the worker treats Goong as unavailable and skips the call instead of
 *     spending retry budget against an obviously sick provider
 *   - the user-facing read path is unaffected (it never calls Goong)
 *
 * Recovery is automatic: after the cooldown the gate flips back to HEALTHY
 * and one call is allowed through (the "trial" call). If it succeeds the
 * counters reset; if it fails again the cooldown re-engages.
 *
 * This is NOT a full circuit breaker (no half-open state machine, no slow-
 * call detection). It is a deliberate, ~30-line dampener — easy to reason
 * about, easy to test, sufficient to stop retry storms during a Goong dip.
 */
@Slf4j
@Component
public class GoongHealthGate {

    /** Failures within {@link #WINDOW_MS} to trip the gate. */
    private static final int FAILURE_THRESHOLD = 5;

    /** Rolling-count window. Older failures are dropped on observe(). */
    private static final long WINDOW_MS = 60_000L;

    /** How long the gate stays DEGRADED before the next trial call. */
    private static final long COOLDOWN_MS = 60_000L;

    public enum State { HEALTHY, DEGRADED }

    @Getter private volatile State state = State.HEALTHY;

    /** Wall-clock millis of the oldest failure still in the window. */
    private final AtomicLong windowStartMs = new AtomicLong(0);

    /** Count of failures in the current window. */
    private final AtomicInteger windowFailures = new AtomicInteger(0);

    /** Wall-clock millis when DEGRADED cooldown elapses. */
    private final AtomicReference<Instant> degradedUntil = new AtomicReference<>(Instant.EPOCH);

    /**
     * True if the caller should SKIP its Goong call right now.
     * Cheap (volatile + 1 compare) — safe to call on every outbound op.
     */
    public boolean shouldSkip() {
        if (state == State.HEALTHY) return false;
        if (Instant.now().isAfter(degradedUntil.get())) {
            // Cooldown elapsed — let one trial call through. If it fails the
            // gate re-engages via observeFailure(); if it succeeds counters
            // are cleared via observeSuccess().
            transitionTo(State.HEALTHY, "cooldown elapsed");
            return false;
        }
        return true;
    }

    /** Record a successful Goong call. Clears the failure window. */
    public void observeSuccess() {
        if (windowFailures.get() != 0) {
            windowFailures.set(0);
            windowStartMs.set(0);
        }
        if (state == State.DEGRADED) {
            transitionTo(State.HEALTHY, "trial call succeeded");
        }
    }

    /**
     * Record a transient failure (429 / 5xx / I/O). Trips the gate when
     * {@link #FAILURE_THRESHOLD} failures have happened within
     * {@link #WINDOW_MS}.
     */
    public void observeFailure(String reason) {
        long now = System.currentTimeMillis();
        long start = windowStartMs.get();
        if (start == 0 || (now - start) > WINDOW_MS) {
            // Reset the window — single-writer race is benign here, worst
            // case we under-count by 1 in a tight ~µs window.
            windowStartMs.set(now);
            windowFailures.set(1);
        } else {
            windowFailures.incrementAndGet();
        }

        int count = windowFailures.get();
        if (count >= FAILURE_THRESHOLD && state == State.HEALTHY) {
            degradedUntil.set(Instant.now().plus(Duration.ofMillis(COOLDOWN_MS)));
            transitionTo(State.DEGRADED,
                "failure threshold reached (" + count + " in window, last reason=" + reason + ")");
        }
    }

    /** Snapshot for /internal/health admin endpoint. */
    public Snapshot snapshot() {
        return new Snapshot(
            state,
            windowFailures.get(),
            degradedUntil.get()
        );
    }

    private void transitionTo(State next, String reason) {
        State prev = this.state;
        if (prev == next) return;
        this.state = next;
        log.warn("GoongHealthGate {} → {} ({})", prev, next, reason);
    }

    public record Snapshot(State state, int failuresInWindow, Instant degradedUntil) {}
}
