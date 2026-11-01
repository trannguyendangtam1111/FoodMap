package com.foodmap.ingestion;

import com.foodmap.config.properties.IngestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, deduplicating producer/consumer queue between the read path
 * (which enqueues cells it discovered are stale) and the IngestionWorker
 * pool (which drains and calls Goong).
 *
 * DESIGN POINTS
 *   - Bounded LinkedBlockingQueue caps memory at queueCapacity. When full,
 *     offer() returns false; the producer logs and moves on. We'd rather
 *     drop ingestion than block the user-facing request.
 *   - The inFlight Set deduplicates two ways: (a) a cell that's queued but
 *     not yet picked up, (b) a cell currently being processed. Without
 *     this, panning back and forth across a stale tile would enqueue it
 *     hundreds of times.
 *   - markCompleted() must be called by the worker after it finishes
 *     processing — both on success and failure paths. We use a try/finally
 *     in the worker to guarantee this.
 */
@Slf4j
@Component
public class IngestionQueue {

    private final LinkedBlockingQueue<IngestionTask> queue;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final int capacity;

    public IngestionQueue(IngestionProperties properties) {
        this.capacity = properties.getQueueCapacity();
        this.queue    = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Try to enqueue a cell for ingestion.
     * Returns true if accepted, false if the queue was full or the cell
     * is already queued/in-flight. Never blocks.
     */
    public boolean offer(IngestionTask task) {
        if (!inFlight.add(task.cellKey())) {
            // Already queued or being processed — silently dedupe.
            return false;
        }
        boolean accepted = queue.offer(task);
        if (!accepted) {
            // Queue was full — release the in-flight reservation so a
            // later retry can succeed.
            inFlight.remove(task.cellKey());
            log.warn("Ingestion queue full ({} entries) — dropping cell {}",
                capacity, task.cellKey());
        } else {
            log.debug("Enqueued cell {} (queue size now ~{}, in-flight ~{})",
                task.cellKey(), queue.size(), inFlight.size());
        }
        return accepted;
    }

    /**
     * Block until a task is available, or the timeout elapses, or the
     * thread is interrupted. Used by IngestionWorker's main loop.
     */
    public IngestionTask poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * Release the in-flight reservation for a cell. Worker calls this
     * in a finally block after processing finishes (success or failure)
     * so the same cell can be re-enqueued by future requests.
     */
    public void markCompleted(String cellKey) {
        inFlight.remove(cellKey);
    }

    /** Diagnostics — exposed for an admin endpoint or metrics scrape. */
    public int queuedSize()   { return queue.size(); }
    public int inFlightSize() { return inFlight.size(); }
}
