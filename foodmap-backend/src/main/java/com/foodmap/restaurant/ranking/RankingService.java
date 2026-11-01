package com.foodmap.restaurant.ranking;

import com.foodmap.common.util.GeoUtils;
import com.foodmap.restaurant.domain.Restaurant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Re-ranks a pre-filtered list of candidate restaurants using the
 * configurable composite scoring formula in {@link PlaceScorer}.
 *
 * This sits between the DB retrieval layer (PostGIS spatial queries,
 * which return a geographically bounded candidate set) and the
 * response mapping layer. It never touches the database itself.
 *
 * ── Architecture note ────────────────────────────────────────────────────
 *   Candidates come from PostGIS with an upper-bound cap (e.g., 200 rows).
 *   Re-ranking 200 restaurants in Java is ~microseconds — no concern.
 *   This approach keeps the scoring formula in one place, configurable
 *   without touching SQL, and testable without a database.
 *
 * ── Future personalization extension points ──────────────────────────────
 *   1. Click-through rate signal: pass ClickTrackingService counts into
 *      PersonalizationContext and add a ctr component to PlaceScorer.
 *   2. Favourite categories: load user's top-clicked cuisines from
 *      ClickEventRepository and set preferredCategories on the context;
 *      PlaceScorer can apply a fractional boost per matching category.
 *   3. Trending signal: query TrendingScoreRepository for DAILY scores,
 *      add a trendingScore factor with its own weight.
 *   In each case: add a field to PersonalizationContext, extend PlaceScorer,
 *   add a weight to RankingProperties — no other code needs to change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private final PlaceScorer scorer;

    /**
     * Scores and re-ranks {@code candidates} by relevance to the user's position.
     *
     * @param candidates   spatially pre-filtered list from the repository
     * @param userLat      user (or viewport-center) latitude for distance calculation
     * @param userLng      user (or viewport-center) longitude for distance calculation
     * @param ctx          personalization context (use {@link PersonalizationContext#anonymous()} when no session)
     * @return             candidates sorted by descending composite score
     */
    public List<Restaurant> rank(List<Restaurant> candidates,
                                  double userLat, double userLng,
                                  PersonalizationContext ctx) {
        if (candidates.isEmpty()) return candidates;

        record Scored(Restaurant restaurant, ScoreFactors factors) {}

        List<Scored> scored = candidates.stream()
            .map(r -> {
                double distMeters = GeoUtils.distanceMeters(userLat, userLng,
                                                             r.getLatitude(), r.getLongitude());
                return new Scored(r, scorer.score(r, distMeters, ctx));
            })
            .toList();

        if (log.isTraceEnabled()) {
            scored.forEach(s -> log.trace("rank id={} name='{}' {}",
                s.restaurant().getId(), s.restaurant().getName(), s.factors()));
        }

        return scored.stream()
            .sorted(Comparator.comparingDouble(s -> -s.factors().total()))
            .map(Scored::restaurant)
            .toList();
    }
}
