package com.foodmap.integration.goong;

import com.foodmap.common.exception.GoongIntegrationException;
import com.foodmap.config.properties.GoongProperties;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Central token-bucket limiter for ALL outbound Goong calls.
 *
 * Built on Guava's RateLimiter (smooth bursty mode) so the rate is enforced
 * across every caller in the JVM — workers, schedulers, manual sync all
 * share one bucket. Rate is taken from {@link GoongProperties#getMaxRequestsPerSecond()}
 * so it can be tuned per environment without a redeploy.
 *
 * Acquire is bounded by a timeout. Under saturation a caller fails fast with
 * a GoongIntegrationException instead of blocking the worker thread forever
 * — that is what lets the queue drain in a controlled way and lets retries
 * be honest about whether they were actually attempted.
 *
 * Per-JVM by design. When the app scales horizontally, swap this for a
 * Redis-backed bucket (Bucket4j-Redis) — the public API stays the same.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoongRateLimiter {

    /** Hard ceiling on how long a caller will block waiting for a permit. */
    private static final long ACQUIRE_TIMEOUT_MS = 5_000L;

    private final GoongProperties goongProperties;

    private volatile RateLimiter limiter;

    @PostConstruct
    void init() {
        double rps = Math.max(0.1, goongProperties.getMaxRequestsPerSecond());
        this.limiter = RateLimiter.create(rps);
        log.info("GoongRateLimiter initialised at {} rps (timeout {} ms)", rps, ACQUIRE_TIMEOUT_MS);
    }

    /**
     * Reserve one permit. Blocks up to {@value #ACQUIRE_TIMEOUT_MS} ms.
     * Throws GoongIntegrationException if the wait exceeds the timeout —
     * fail fast under saturation rather than queueing more work.
     */
    public void acquire() {
        long startNs = System.nanoTime();
        boolean got = limiter.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        long waitedMs = (System.nanoTime() - startNs) / 1_000_000L;
        if (!got) {
            log.warn("Goong rate-limit acquire timed out after {} ms — backpressure", waitedMs);
            throw new GoongIntegrationException(
                "Goong rate limit saturated (waited " + waitedMs + "ms)");
        }
        if (waitedMs > 250) {
            log.debug("Waited {} ms for Goong rate-limit permit", waitedMs);
        }
    }
}
