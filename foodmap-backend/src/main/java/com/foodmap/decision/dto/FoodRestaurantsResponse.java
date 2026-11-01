package com.foodmap.decision.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foodmap.decision.dto.AvailableFoodsResponse.ScopeDTO;
import com.foodmap.decision.dto.FoodDecisionResponse.FoodRef;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;

import java.util.List;

/**
 * GET /api/v1/decisions/foods/{key}/restaurants — every restaurant of the
 * scope FoodMap knows to have the food, in the ranking order, one page.
 *
 * @param scope         the scope, with the radius actually used and whether it was widened
 *                      (never coordinates)
 * @param food          the requested food (public catalog fields only); never a substitute
 * @param totalElements every restaurant with the food at that radius among the rows evaluated — the
 *                      same number GET /decisions/foods/available reports for the food in the same scope
 * @param totalCapped   true when the rows read for the scope hit the safety cap: then totalElements is a
 *                      lower bound ("at least N"), not an exact count, and the pages cover only the
 *                      evaluated rows; false means totalElements is exact
 * @param page          zero-based page index
 * @param size          page size (1–50)
 * @param items         this page, ranked; empty past the last page or when none has the food
 */
public record FoodRestaurantsResponse(
    ScopeDTO scope,
    FoodRef food,
    int totalElements,
    boolean totalCapped,
    int page,
    int size,
    List<Item> items
) {

    /**
     * @param restaurant     the restaurant, classified by the server ({@code foodKeys}, {@code category})
     * @param distanceMeters real distance from the user — USER scope only, omitted otherwise
     * @param evidence       why FoodMap knows this restaurant has the food
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(RestaurantSummaryDTO restaurant, Integer distanceMeters, Evidence evidence) {}

    /**
     * @param source   NAME (the restaurant's name names the food) or MENU (a FoodMap menu dish does)
     * @param dishName the matching FoodMap menu dish (MENU only)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(String source, String dishName) {}
}
