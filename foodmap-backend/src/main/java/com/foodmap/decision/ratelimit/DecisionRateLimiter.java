package com.foodmap.decision.ratelimit;

import com.foodmap.config.properties.DecisionProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Per-client token bucket guarding GET /api/v1/decisions/today only.
 *
 * The project has no inbound rate limiting (GoongRateLimiter throttles
 * outbound provider calls), so this is the smallest isolated limiter:
 *   key      client IP (request.getRemoteAddr())
 *   bucket   `capacity` tokens, refilled at `refillPerMinute`
 *   storage  in-memory Caffeine map, idle keys expire after 10 min, ≤ 10k keys
 *
 * Limitations (acceptable for the current single-instance deployment):
 *   - per JVM: several backend instances would each allow the full rate;
 *   - X-Forwarded-For is deliberately not trusted, so behind a reverse
 *     proxy every client shares the proxy's IP until the proxy is configured
 *     (e.g. server.forward-headers-strategy) — revisit before production;
 *   - state resets on restart.
 */
@Component
public class DecisionRateLimiter {

    public record Result(boolean allowed, long retryAfterSeconds) {}

    private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();

    private final int capacity;
    private final double tokensPerNano;
    private final LongSupplier clock;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(10))
        .maximumSize(10_000)
        .build();

    @Autowired
    public DecisionRateLimiter(DecisionProperties props) {
        this(props.getRateLimit().getCapacity(), props.getRateLimit().getRefillPerMinute(), System::nanoTime);
    }

    DecisionRateLimiter(int capacity, int refillPerMinute, LongSupplier clock) {
        this.capacity = Math.max(1, capacity);
        this.tokensPerNano = Math.max(1, refillPerMinute) / (double) NANOS_PER_MINUTE;
        this.clock = clock;
    }

    public Result tryConsume(String clientKey) {
        Bucket bucket = buckets.get(clientKey == null ? "unknown" : clientKey,
            k -> new Bucket(capacity, clock.getAsLong()));
        synchronized (bucket) {
            long now = clock.getAsLong();
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.updatedAt) * tokensPerNano);
            bucket.updatedAt = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return new Result(true, 0);
            }
            double nanosUntilToken = (1 - bucket.tokens) / tokensPerNano;
            return new Result(false, Math.max(1, (long) Math.ceil(nanosUntilToken / 1_000_000_000d)));
        }
    }

    private static final class Bucket {
        double tokens;
        long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }
}
