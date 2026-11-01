package com.foodmap.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-window suppression for viewport-driven cell enqueues.
 *
 * The {@link IngestionQueue} already dedupes a cell that is queued or in
 * flight. But it cannot dedupe a cell whose crawl just *finished* — the
 * cell's coverage row sets a long {@code next_eligible_at}, yet between
 * the worker completing and the row being committed there is a small
 * window where the read path could re-enqueue.
 *
 * More importantly, when a user pans the map rapidly (1–5 viewport
 * requests/sec), every request re-runs the bbox → cells → enqueue loop.
 * Even if every individual cell is correctly deduped, the bookkeeping
 * (DB probe + set arithmetic) is wasted work and the worker still sees
 * a burst whenever a tiny gap opens in the inFlight set.
 *
 * This gate keeps a small in-memory map of "last enqueue attempt at
 * timestamp X for cell C" and suppresses repeat attempts that arrive
 * within the cooldown window. Independent of the DB cooldown — this
 * is a request-side burst dampener, not a coverage decision.
 */
@Slf4j
@Component
public class ViewportEnqueueGate {

    /** Don't re-enqueue the same cell within this window from a viewport read. */
    private static final long ENQUEUE_COOLDOWN_MS = 30_000L;

    /** Soft cap to keep the map from growing unbounded under crawler-style traffic. */
    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, Long> lastEnqueuedAt = new ConcurrentHashMap<>();

    /**
     * Returns true if the cell may be enqueued now, false if it was enqueued
     * within the cooldown window. Atomic — only one concurrent caller per
     * cell wins.
     */
    public boolean tryEnqueue(String cellKey) {
        long now = System.currentTimeMillis();
        Long prev = lastEnqueuedAt.compute(cellKey, (k, v) -> {
            if (v != null && (now - v) < ENQUEUE_COOLDOWN_MS) {
                return v;          // still cooling down — keep the old timestamp
            }
            return now;
        });
        boolean allowed = (prev == now);
        if (!allowed) {
            log.trace("ViewportEnqueueGate suppressed cell {} (cooldown {} ms)",
                cellKey, ENQUEUE_COOLDOWN_MS - (now - prev));
        }
        if (lastEnqueuedAt.size() > MAX_ENTRIES) {
            evictOldEntries(now);
        }
        return allowed;
    }

    /** Drop entries older than the cooldown window. Best-effort, single pass. */
    private void evictOldEntries(long now) {
        lastEnqueuedAt.entrySet().removeIf(e -> (now - e.getValue()) > ENQUEUE_COOLDOWN_MS);
    }

    int sizeForTest() {
        return lastEnqueuedAt.size();
    }
}
