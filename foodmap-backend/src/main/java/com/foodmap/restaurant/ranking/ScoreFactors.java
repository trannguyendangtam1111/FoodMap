package com.foodmap.restaurant.ranking;

/**
 * Breakdown of an individual place's score, used for logging and debugging.
 * Each component is in [0, 1]; total is the weighted sum.
 */
public record ScoreFactors(
    double distance,
    double popularity,
    double recency,
    double categoryRelevance,
    double total
) {
    @Override
    public String toString() {
        return "ScoreFactors{dist=%.3f pop=%.3f rec=%.3f cat=%.3f → total=%.4f}"
            .formatted(distance, popularity, recency, categoryRelevance, total);
    }
}
