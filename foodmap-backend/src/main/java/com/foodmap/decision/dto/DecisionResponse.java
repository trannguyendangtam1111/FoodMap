package com.foodmap.decision.dto;

import com.foodmap.decision.dto.AvailableFoodsResponse.AreaRef;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;

import java.util.List;
import java.util.UUID;

/**
 * Result of GET /api/v1/decisions/today (with or without {@code food}).
 *
 * When no restaurant qualifies, the call still succeeds (200) with
 * {@code restaurant = null}, {@code decisionId = null} and
 * {@code candidateCount = 0} — "nothing nearby" is a normal outcome.
 *
 * @param decisionId     unique per successful decision; not persisted
 * @param restaurant     the chosen restaurant (same shape as list/search results)
 * @param distanceMeters real distance from the user (USER scope only) — null when the
 *                       scope is not USER (an area or the city centre is never a distance)
 * @param reasons        factual reasons; each present only when its data exists
 * @param candidateCount eligible candidates at the stage that produced the
 *                       decision (after exclusions and, if applied, the mood)
 * @param fallbackLevel  0 exact · 1 mood relaxed · 2 radius widened ·
 *                       3 no real location (city-centre pool)
 * @param locationSource USER, AREA or CITY_CENTER
 * @param radiusMeters   radius actually used for the final stage
 * @param mood           requested mood slug, or null
 * @param moodMatched    whether the chosen restaurant fits the mood; null when no mood was requested
 * @param food           the requested food ({@code food=}), or null; never a substitute
 * @param area           FoodMap discovery area (key, label) for an AREA scope; null otherwise
 * @param radiusWidened  the radius was widened beyond the requested/base radius
 * @param emptyReason    why restaurant is null: NO_RESTAURANT_IN_SCOPE (no food requested),
 *                       NO_FOOD_IN_SCOPE (no restaurant has the food), ALL_SHOWN (all excluded);
 *                       null when a restaurant was chosen
 */
public record DecisionResponse(
    UUID decisionId,
    RestaurantSummaryDTO restaurant,
    Integer distanceMeters,
    List<DecisionReason> reasons,
    int candidateCount,
    int fallbackLevel,
    String locationSource,
    int radiusMeters,
    String mood,
    Boolean moodMatched,
    FoodRef food,
    AreaRef area,
    boolean radiusWidened,
    String emptyReason
) {

    public static final String NO_RESTAURANT_IN_SCOPE = "NO_RESTAURANT_IN_SCOPE";
    public static final String NO_FOOD_IN_SCOPE = "NO_FOOD_IN_SCOPE";
    public static final String ALL_SHOWN = "ALL_SHOWN";

    /** The food a restaurant decision was asked for. */
    public record FoodRef(String key, String label) {}
}
