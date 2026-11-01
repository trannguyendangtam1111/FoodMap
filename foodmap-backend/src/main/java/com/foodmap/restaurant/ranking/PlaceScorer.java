package com.foodmap.restaurant.ranking;

import com.foodmap.restaurant.domain.Restaurant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Computes a composite relevance score for a single place.
 *
 * All scoring functions are pure (no I/O) and return values in [0, 1].
 * The scorer is stateless — inject it anywhere without threading concerns.
 *
 * ── Formula ──────────────────────────────────────────────────────────────
 *
 *   score = w_dist × distanceScore
 *         + w_pop  × popularityScore
 *         + w_rec  × recencyScore
 *         + w_cat  × categoryScore
 *
 *   distanceScore   = 1 / (1 + km / halfLifeKm)
 *     → 1.0 at origin, 0.5 at halfLifeKm, 0.33 at 2×halfLifeKm
 *     → decays smoothly — a place 5 km away still appears if it dominates
 *       the other signals.
 *
 *   popularityScore = (avgRating / 5) × ln(1 + reviews) / ln(1 + maxReviews)
 *     → combines star quality (rating fraction) with engagement depth (log reviews).
 *     → log-damping prevents a place with 10 000 reviews from permanently
 *       burying a newer, equally-rated place.
 *
 *   recencyScore    = max(0, 1 − daysSinceSync / maxAgeDays)
 *     → freshly synced data scores 1.0; stale data trends toward 0.
 *     → null lastSyncedAt → 0.5 (neutral, not penalised for just being ingested).
 *
 *   categoryScore   = 1.0 if cuisineType matches categoryQuery (case-insensitive)
 *                   = 0.0 if no filter active or no match
 *     → acts as a precision boost, not a gate: a non-matching restaurant still
 *       appears if its other signals are strong enough.
 */
@Component
@RequiredArgsConstructor
public class PlaceScorer {

    private final RankingProperties props;

    public ScoreFactors score(Restaurant restaurant, double distanceMeters,
                              PersonalizationContext ctx) {
        double dist = distanceScore(distanceMeters);
        double pop  = popularityScore(restaurant);
        double rec  = recencyScore(restaurant);
        double cat  = categoryScore(restaurant, ctx);

        RankingProperties.Weights w = props.getWeights();
        double total = w.getDistance()          * dist
                     + w.getPopularity()        * pop
                     + w.getRecency()           * rec
                     + w.getCategoryRelevance() * cat;

        return new ScoreFactors(dist, pop, rec, cat, total);
    }

    // ── Individual factor functions ───────────────────────────────────────────

    private double distanceScore(double distanceMeters) {
        double km = distanceMeters / 1000.0;
        return 1.0 / (1.0 + km / props.getDistance().getHalfLifeKm());
    }

    private double popularityScore(Restaurant r) {
        double ratingFraction = (r.getAvgRating() == null || r.getAvgRating().doubleValue() == 0)
            ? 0.0
            : r.getAvgRating().doubleValue() / 5.0;
        double logReviews = Math.log1p(r.getReviewCount());
        double logCeiling = Math.log1p(props.getPopularity().getMaxReviewCount());
        // Clamp to [0, 1] in case reviewCount somehow exceeds the configured ceiling.
        return ratingFraction * Math.min(1.0, logReviews / logCeiling);
    }

    private double recencyScore(Restaurant r) {
        if (r.getLastSyncedAt() == null) return 0.5;
        long days = Duration.between(r.getLastSyncedAt(), Instant.now()).toDays();
        return Math.max(0.0, 1.0 - (double) days / props.getRecency().getMaxAgeDays());
    }

    private double categoryScore(Restaurant r, PersonalizationContext ctx) {
        if (ctx.categoryQuery() == null || ctx.categoryQuery().isBlank()) return 0.0;
        if (r.getCuisineType() == null) return 0.0;
        return r.getCuisineType().trim().equalsIgnoreCase(ctx.categoryQuery().trim()) ? 1.0 : 0.0;
    }
}
