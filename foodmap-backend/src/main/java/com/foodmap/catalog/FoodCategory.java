package com.foodmap.catalog;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The 12 presentation categories the frontend draws with CategoryArt, each
 * with its default tone. Keys and tones mirror the frontend's design system
 * (utils/category.js, CategoryArt TONES), so they live in code, not in the
 * catalog file.
 */
public enum FoodCategory {
    NOODLE("noodle", "chili"),
    HOTPOT("hotpot", "chili"),
    RICE("rice", "turmeric"),
    VIETNAMESE("vietnamese", "turmeric"),
    BAKERY("bakery", "crust"),
    PIZZA("pizza", "crust"),
    COFFEE("coffee", "taro"),
    VEGETARIAN("vegetarian", "pandan"),
    DESSERT("dessert", "dragonfruit"),
    JAPANESE("japanese", "dragonfruit"),
    SEAFOOD("seafood", "brand"),
    OTHER("other", "brand");

    /** Accent tones that exist in the frontend design system. */
    public static final Set<String> TONES =
        Set.of("chili", "turmeric", "crust", "taro", "pandan", "dragonfruit", "brand");

    private final String key;
    private final String defaultTone;

    FoodCategory(String key, String defaultTone) {
        this.key = key;
        this.defaultTone = defaultTone;
    }

    public String key() { return key; }
    public String defaultTone() { return defaultTone; }

    public static Optional<FoodCategory> fromKey(String key) {
        return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
    }
}
