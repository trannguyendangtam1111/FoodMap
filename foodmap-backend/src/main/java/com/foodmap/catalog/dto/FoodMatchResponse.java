package com.foodmap.catalog.dto;

import java.util.List;

/**
 * GET /api/v1/catalog/foods/match — discoverable foods a search-box query refers to.
 *
 * @param foods  suggestions: the food the query names first, then foods it is typing or mentions
 * @param intent the one discoverable food the WHOLE query names ("phở", "pho", "bún bò"), or null —
 *               a longer query ("phở bò", "Phở Hòa Pasteur") or an unknown word names no food
 */
public record FoodMatchResponse(List<FoodRef> foods, FoodRef intent) {

    public record FoodRef(String key, String label) {}
}
