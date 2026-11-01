package com.foodmap.food.match;

/**
 * Why a restaurant carries a food key. Server-internal: only the key and
 * category reach clients.
 *
 * @param dishName the FoodMap menu dish that matched (MENU only)
 */
public record FoodEvidence(Source source, String dishName) {

    public enum Source { NAME, MENU }

    public static FoodEvidence name() {
        return new FoodEvidence(Source.NAME, null);
    }

    public static FoodEvidence menu(String dishName) {
        return new FoodEvidence(Source.MENU, dishName);
    }
}
