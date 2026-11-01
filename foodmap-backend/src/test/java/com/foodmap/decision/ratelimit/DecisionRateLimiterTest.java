package com.foodmap.decision.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Token bucket per client key, driven by a fake clock. */
class DecisionRateLimiterTest {

    private final AtomicLong now = new AtomicLong(0);
    private final DecisionRateLimiter limiter = new DecisionRateLimiter(3, 6, now::get); // burst 3, 6/min = 1 per 10 s

    @Test
    void allowsBurst_thenRejectsWithRetryAfter() {
        assertThat(limiter.tryConsume("1.1.1.1").allowed()).isTrue();
        assertThat(limiter.tryConsume("1.1.1.1").allowed()).isTrue();
        assertThat(limiter.tryConsume("1.1.1.1").allowed()).isTrue();
        DecisionRateLimiter.Result denied = limiter.tryConsume("1.1.1.1");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 3; i++) limiter.tryConsume("2.2.2.2");
        assertThat(limiter.tryConsume("2.2.2.2").allowed()).isFalse();

        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(limiter.tryConsume("2.2.2.2").allowed()).isTrue();
        assertThat(limiter.tryConsume("2.2.2.2").allowed()).isFalse();
    }

    @Test
    void neverExceedsCapacity_afterLongIdle() {
        now.addAndGet(Duration.ofHours(1).toNanos());
        int allowed = 0;
        for (int i = 0; i < 10; i++) if (limiter.tryConsume("3.3.3.3").allowed()) allowed++;
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void clientsHaveIndependentBuckets() {
        for (int i = 0; i < 3; i++) limiter.tryConsume("4.4.4.4");
        assertThat(limiter.tryConsume("4.4.4.4").allowed()).isFalse();
        assertThat(limiter.tryConsume("5.5.5.5").allowed()).isTrue();
    }
}
