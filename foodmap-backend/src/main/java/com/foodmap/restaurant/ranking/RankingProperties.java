package com.foodmap.restaurant.ranking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the place-ranking formula. All defaults are set here
 * so the app starts without explicit config; override in application.yml
 * under the "app.ranking" prefix.
 *
 * Formula:
 *   score = w.distance          × distanceScore(halfLifeKm)
 *         + w.popularity        × popularityScore(maxReviewCount)
 *         + w.recency           × recencyScore(maxAgeDays)
 *         + w.categoryRelevance × categoryScore
 *
 * Invariant: weights should sum to 1.0 (not enforced in code, just convention).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ranking")
public class RankingProperties {

    private Weights weights = new Weights();
    private Distance distance = new Distance();
    private Popularity popularity = new Popularity();
    private Recency recency = new Recency();

    @Getter
    @Setter
    public static class Weights {
        /** How much to favour closer places. */
        private double distance = 0.50;
        /** How much to favour popular (highly rated + many reviews) places. */
        private double popularity = 0.30;
        /** How much to favour recently synced places. */
        private double recency = 0.10;
        /** Boost for places whose cuisine matches the active category filter. */
        private double categoryRelevance = 0.10;
    }

    @Getter
    @Setter
    public static class Distance {
        /**
         * Distance in km at which the distance score drops to 0.5.
         * Lower values favour hyper-local results; higher values spread results wider.
         */
        private double halfLifeKm = 1.0;
    }

    @Getter
    @Setter
    public static class Popularity {
        /**
         * Review count used as the log-normalisation ceiling.
         * A place with this many reviews gets the maximum engagement multiplier.
         * Prevents mega-chains with 10 000 reviews from dominating indefinitely.
         */
        private int maxReviewCount = 100;
    }

    @Getter
    @Setter
    public static class Recency {
        /**
         * Number of days after which a place's recency score reaches 0.0.
         * Data synced today scores 1.0; data this many days old scores 0.0.
         */
        private int maxAgeDays = 365;
    }
}
