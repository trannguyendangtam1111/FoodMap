package com.foodmap.decision.dto;

import com.foodmap.decision.dto.AvailableFoodsResponse.ScopeDTO;

import java.util.UUID;

/**
 * Result of GET /api/v1/decisions/food — the cà mèn reveals a FOOD.
 *
 * When nothing can be offered the call still succeeds (200) with
 * {@code food = null}, {@code decisionId = null} and an {@code emptyReason}.
 *
 * @param decisionId         unique per successful decision; not persisted
 * @param food               the chosen food (public catalog fields only), or null
 * @param restaurantCount    restaurants FoodMap knows with this food within {@code radiusMeters}
 *                           — supply, not popularity
 * @param restaurantCountCapped true when the rows read for the scope hit the safety cap: the count
 *                           is then a lower bound ("at least N"); false means it is exact
 * @param radiusMeters       radius the count is for
 * @param radiusWidened      true when the base radius had none of this food
 * @param scope              the scope the draw ran in (USER, AREA or CITY_CENTER; never coordinates)
 * @param candidateFoodCount foods the draw was made among (available, requested kinds, not excluded)
 * @param emptyReason        NO_FOOD_IN_SCOPE (nothing available) or ALL_SHOWN (all excluded); null otherwise
 */
public record FoodDecisionResponse(
    UUID decisionId,
    FoodRef food,
    int restaurantCount,
    boolean restaurantCountCapped,
    int radiusMeters,
    boolean radiusWidened,
    ScopeDTO scope,
    int candidateFoodCount,
    String emptyReason
) {

    public static final String NO_FOOD_IN_SCOPE = DecisionResponse.NO_FOOD_IN_SCOPE;
    public static final String ALL_SHOWN = DecisionResponse.ALL_SHOWN;

    /** The chosen food: exactly the public catalog fields. */
    public record FoodRef(String key, String label, String kind, String parentCategory, String tone, String artKey) {}
}
