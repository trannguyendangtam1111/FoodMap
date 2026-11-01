package com.foodmap.decision.dto;

import java.util.List;

/**
 * GET /api/v1/decisions/foods/available.
 *
 * Counts are supply FoodMap knows (restaurants whose name or FoodMap menu
 * names the food) — never popularity, rating, price or opening status.
 *
 * @param foods            only foods with at least one restaurant, in catalog order
 * @param unavailableCount discoverable foods of the requested kinds with none in this scope
 */
public record AvailableFoodsResponse(ScopeDTO scope, List<AvailableFood> foods, int unavailableCount) {

    /**
     * @param locationSource USER, AREA or CITY_CENTER
     * @param area          key and label of an AREA scope; null otherwise (never coordinates)
     * @param radiusMeters  the scope's base radius
     * @param label         ready-to-show Vietnamese phrase, e.g. "Quanh trung tâm TP.HCM"
     */
    public record ScopeDTO(String locationSource, AreaRef area, int radiusMeters, boolean radiusWidened, String label) {}

    /** A FoodMap discovery area: a search area, not an administrative boundary. */
    public record AreaRef(String key, String label) {}

    /**
     * @param count         restaurants within {@code radiusMeters}
     * @param countCapped   true when the rows read for the scope hit the safety cap: count is then a
     *                      lower bound ("at least N"); false means count is exact
     * @param radiusMeters  the radius the count is for (base, or widened when the base had none)
     */
    public record AvailableFood(String key, String label, String kind, String parentCategory, String tone,
                                int count, boolean countCapped, int radiusMeters, boolean radiusWidened) {}
}
