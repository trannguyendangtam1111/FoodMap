package com.foodmap.decision.ratelimit;

import com.foodmap.config.properties.DecisionProperties;
import org.springframework.stereotype.Component;

/**
 * Per-client bucket for the browsing endpoints under /api/v1/decisions
 * (available foods), kept apart from the decision bucket so browsing never
 * spends the user's decisions. Same mechanics as {@link DecisionRateLimiter}.
 */
@Component
public class BrowseRateLimiter {

    private final DecisionRateLimiter bucket;

    public BrowseRateLimiter(DecisionProperties props) {
        this.bucket = new DecisionRateLimiter(props.getBrowseRateLimit().getCapacity(),
            props.getBrowseRateLimit().getRefillPerMinute(), System::nanoTime);
    }

    public DecisionRateLimiter.Result tryConsume(String clientKey) {
        return bucket.tryConsume(clientKey);
    }
}
