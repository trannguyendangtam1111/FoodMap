package com.foodmap.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.goong")
public class GoongProperties {

    private String apiKey;
    private String mapKey;
    private String baseUrl;
    /**
     * HTTP connect timeout for outbound Goong calls. Defaults are deliberately
     * non-zero — a missing or zero value in config would otherwise be interpreted
     * by SimpleClientHttpRequestFactory as "no timeout", letting a stuck Goong
     * endpoint block an ingestion worker thread indefinitely.
     */
    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs    = 10_000;
    /**
     * Global outbound Goong RPS. Provider-safe default of 1 RPS — raise
     * deliberately. Shared across every caller in the JVM via GoongRateLimiter.
     */
    private double maxRequestsPerSecond = 1.0;

    /** Total attempts including the first call (so 3 = 1 try + 2 retries). */
    private int maxRetryAttempts = 3;

    /**
     * Exponential backoff schedule for 429 / 5xx / I/O retries. Each step
     * has random jitter added (±25%) so retries don't synchronise across
     * worker threads. Index = retry number (after the failed first call).
     *   retry 1 → 2s
     *   retry 2 → 5s
     *   retry 3 → 15s
     * Values past the configured maxRetryAttempts are ignored.
     */
    private java.util.List<Long> retryBackoffMs = java.util.List.of(2_000L, 5_000L, 15_000L);

    /** Per-call ceiling on retry backoff sleep — guardrail for misconfig. */
    private long retryMaxBackoffMs = 30_000L;
}
