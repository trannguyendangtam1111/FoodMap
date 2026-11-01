package com.foodmap.food.match;

import java.util.List;
import java.util.Map;

/**
 * Server-side classification of one restaurant, computed from its name, its
 * FoodMap menu and the food catalog. Never stored.
 *
 * @param foodKeys every matched food key: name matches first (earliest in the
 *                 name, longer phrase first on a tie), then menu-only matches
 *                 in catalog order
 * @param category CategoryArt key, or null when unknown (the client shows "other")
 * @param evidence per food key, where it came from
 */
public record FoodClassification(List<String> foodKeys, String category, Map<String, FoodEvidence> evidence) {

    public static final FoodClassification NONE = new FoodClassification(List.of(), null, Map.of());

    public boolean has(String foodKey) {
        return evidence.containsKey(foodKey);
    }
}
