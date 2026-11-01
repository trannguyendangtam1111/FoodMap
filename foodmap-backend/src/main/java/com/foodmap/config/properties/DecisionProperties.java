package com.foodmap.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for GET /api/v1/decisions/today ("Ăn gì hôm nay?").
 * Defaults live here so the app starts without explicit config; override
 * under "app.decision" in application.yml.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.decision")
public class DecisionProperties {

    /** Radius used when the client sends none. */
    private int defaultRadiusM = 1000;

    /** Hard upper bound for a client-supplied radius (larger → 400). */
    private int maxRadiusM = 5000;

    /** Fallback step: widen the requested radius by this factor (capped at maxRadiusM). */
    private int widenFactor = 3;

    /** Max rows read from the radius query per attempt (nearest first). */
    private int candidateCap = 200;

    /** Weighted random draw happens among the top-N ranked candidates. */
    private int poolSize = 10;

    /** Max IDs accepted in `exclude` (larger → 400). */
    private int maxExcludeIds = 50;

    /**
     * Pool centre when the client has no real location. Matches the
     * frontend's own fallback (HCMC centre) so both sides agree.
     */
    private double fallbackLat = 10.7769;
    private double fallbackLng = 106.7009;
    private int fallbackRadiusM = 3000;
    private String fallbackAreaLabel = "trung tâm TP.HCM";

    private RateLimit rateLimit = new RateLimit();

    /**
     * Separate, looser bucket for browsing endpoints (available foods): a
     * page load or scope change, not a decision. Default 60 burst, 60/min.
     */
    private RateLimit browseRateLimit = RateLimit.of(60, 60);

    @Getter
    @Setter
    public static class RateLimit {
        /** Token-bucket burst size per client IP. */
        private int capacity = 20;
        /** Tokens restored per minute per client IP. */
        private int refillPerMinute = 20;

        static RateLimit of(int capacity, int refillPerMinute) {
            RateLimit r = new RateLimit();
            r.setCapacity(capacity);
            r.setRefillPerMinute(refillPerMinute);
            return r;
        }
    }
}
