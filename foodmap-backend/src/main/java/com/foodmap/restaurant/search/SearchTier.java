package com.foodmap.restaurant.search;

/**
 * Relevance tiers of the restaurant search contract, best first.
 * There are no other tiers.
 */
public enum SearchTier {
    /** The whole name is the query phrase. */
    EXACT_NAME,
    /** The query phrase appears as whole words in the name. */
    WHOLE_WORD_NAME,
    /** The query phrase appears as whole words in a dish on the FoodMap menu. */
    FOOD_MENU,
    /** Last-word prefix in the name, or trigram similarity ≥ 0.30 (long queries only). */
    FUZZY_NAME,
    /** Only the address matches (after removing a leading "{name}, "). */
    ADDRESS_ONLY
}
